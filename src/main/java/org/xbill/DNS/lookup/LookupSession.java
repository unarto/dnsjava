// SPDX-License-Identifier: BSD-2-Clause
package org.xbill.DNS.lookup;

import static java.lang.String.format;
import static java.util.Collections.singletonList;
import static java.util.concurrent.CompletableFuture.*;
import static java.util.stream.Collectors.toList;
import static org.xbill.DNS.Type.CNAME;
import static org.xbill.DNS.Type.DNAME;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.Cache;
import org.xbill.DNS.Credibility;
import org.xbill.DNS.DClass;
import org.xbill.DNS.DNAMERecord;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.NameTooLongException;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;
import org.xbill.DNS.SetResponse;
import org.xbill.DNS.Type;

/**
 * LookupSession provides facilities to make DNS Queries. A LookupSession is intended to be long
 * lived, and it's behaviour can be configured using the properties of the LookupSessionBuilder
 * instance returned by the builder() method.
 */
@Builder
public class LookupSession {
  // remember to update javadoc if this is changed for the following two properties.
  public static final int DEFAULT_MAX_ITERATIONS = 16;
  public static final int DEFAULT_NDOTS = 1;
  /** The {@link Resolver} to use to look up records. */
  @NonNull private final Resolver resolver;
  /**
   * Sets the maximum number of CNAME or DNAME redirects allowed before lookups with fail with
   * {@link RedirectOverflowException}. Defaults to {@code 16}.
   */
  @Builder.Default private final int maxRedirects = DEFAULT_MAX_ITERATIONS;
  /**
   * Sets the threshold for the number of dots which much appear in a name before it is considered
   * absolute. The default is {@value #DEFAULT_NDOTS}, meaning meaning that if there are any dots in
   * a name, the name will be tried first as an absolute name.
   */
  @Builder.Default private final int ndots = DEFAULT_NDOTS;

  /** Configures the search path used to look up relative names with less than ndots dots. */
  @Singular("searchPath")
  private final List<Name> searchPath;

  /**
   * If set to {@code true}, cached results with multiple records will be returned with the starting
   * point shifted one step per request.
   */
  @Builder.Default private final boolean cycleResults = false;

  /**
   * Configures the Cache instances to be used for lookups for the different {@link DClass} values.
   */
  @Singular("cache")
  private final Map<Integer, Cache> caches;

  /**
   * A builder for {@link LookupSession} instances where functionality is mostly generated as
   * described in the <a href="https://projectlombok.org/features/Builder">Lombok Builder</a>
   * documentation. An instance of this class is obtained by calling {@link LookupSession#builder()}
   * and configured using the methods with names corresponding to the different properties. Once
   * fully configured, a {@link LookupSession} instance is obtained by calling {@link
   * LookupSessionBuilder#build()} on the builder instance.
   */
  public static class LookupSessionBuilder {
    void preBuild() {
      // note that this transform is idempotent, as concatenating an already absolute Name with root
      // is a noop.
      if (searchPath != null) {
        this.searchPath =
            searchPath.stream()
                .map(
                    name -> {
                      try {
                        return Name.concatenate(name, Name.root);
                      } catch (NameTooLongException e) {
                        throw new IllegalArgumentException("Search path name too long");
                      }
                    })
                .collect(Collectors.toCollection(ArrayList::new));
      }
    }
  }

  /** Returns a new {@link LookupSessionBuilder} instance. */
  public static LookupSessionBuilder builder() {
    return new LookupSessionBuilder() {
      @Override
      public LookupSession build() {
        preBuild();
        return super.build();
      }
    };
  }

  /**
   * Make an asynchronous lookup of the provided name.
   *
   * @param name the name to look up.
   * @param type the type to look up, values should correspond to constants in {@link Type}.
   * @param dclass the class to look up, values should correspond to constants in {@link DClass}.
   * @return A {@link CompletionStage} what will yield the eventual lookup result.
   */
  public CompletionStage<LookupResult> lookupAsync(Name name, int type, int dclass) {
    CompletableFuture<LookupResult> future = new CompletableFuture<>();
    lookupUntilSuccess(expandName(name).iterator(), type, dclass, future);
    return future;
  }

  /**
   * Generate a stream of names according to the search path application semantics. The semantics of
   * this is a bit odd, but they are inherited from Lookup.java. Note that the stream returned is
   * never empty, as it will at the very least always contain {@code name}.
   */
  Stream<Name> expandName(Name name) {
    if (name.isAbsolute()) {
      return Stream.of(name);
    }
    Stream<Name> fromSearchPath =
        Stream.concat(
            searchPath.stream()
                .map(searchSuffix -> safeConcat(name, searchSuffix))
                .filter(Objects::nonNull),
            Stream.of(safeConcat(name, Name.root)));

    if (name.labels() > ndots) {
      return Stream.concat(Stream.of(safeConcat(name, Name.root)), fromSearchPath);
    }
    return fromSearchPath;
  }

  private static Name safeConcat(Name name, Name suffix) {
    try {
      return Name.concatenate(name, suffix);
    } catch (NameTooLongException e) {
      return null;
    }
  }

  private void lookupUntilSuccess(
      Iterator<Name> names, int type, int dclass, CompletableFuture<LookupResult> future) {

    Record query = Record.newRecord(names.next(), type, dclass);
    lookupWithCache(query, null)
        .thenCompose(answer -> resolveRedirects(answer, query))
        .whenComplete(
            (result, ex) -> {
              Throwable cause = ex == null ? null : ex.getCause();
              if (cause instanceof NoSuchDomainException || cause instanceof NoSuchRRSetException) {
                if (names.hasNext()) {
                  lookupUntilSuccess(names, type, dclass, future);
                } else {
                  future.completeExceptionally(cause);
                }
              } else if (cause != null) {
                future.completeExceptionally(cause);
              } else {
                future.complete(result);
              }
            });
  }

  private CompletionStage<LookupResult> lookupWithCache(Record queryRecord, List<Name> aliases) {
    return Optional.ofNullable(caches.get(queryRecord.getDClass()))
        .map(c -> c.lookupRecords(queryRecord.getName(), queryRecord.getType(), Credibility.NORMAL))
        .map(this::setResponseToMessageFuture)
        .orElseGet(() -> lookupWithResolver(queryRecord, aliases));
  }

  private CompletionStage<LookupResult> lookupWithResolver(Record queryRecord, List<Name> aliases) {
    return resolver
        .sendAsync(Message.newQuery(queryRecord))
        .thenApply(this::maybeAddToCache)
        .thenApply(answer -> buildResult(answer, aliases));
  }

  private Message maybeAddToCache(Message message) {
    Optional.ofNullable(caches.get(message.getQuestion().getDClass()))
        .ifPresent(cache -> cache.addMessage(message));
    return message;
  }

  private CompletionStage<LookupResult> setResponseToMessageFuture(SetResponse setResponse) {
    if (setResponse.isNXDOMAIN()) {
      return completeExceptionally(new NoSuchDomainException());
    }
    if (setResponse.isNXRRSET()) {
      return completeExceptionally(new NoSuchRRSetException());
    }
    if (setResponse.isSuccessful()) {
      List<Record> records =
          setResponse.answers().stream()
              .flatMap(rrset -> rrset.rrs(cycleResults).stream())
              .collect(toList());
      return completedFuture(new LookupResult(records, null));
    }
    return null;
  }

  private <T extends LookupFailedException> CompletionStage<LookupResult> completeExceptionally(
      T failure) {
    CompletableFuture<LookupResult> future = new CompletableFuture<>();
    future.completeExceptionally(failure);
    return future;
  }

  private CompletionStage<LookupResult> resolveRedirects(LookupResult response, Record query) {
    CompletableFuture<LookupResult> future = new CompletableFuture<>();
    maybeFollowRedirect(response, query, 1, future);
    return future;
  }

  private void maybeFollowRedirect(
      LookupResult response,
      Record query,
      int redirectCount,
      CompletableFuture<LookupResult> future) {
    try {
      if (redirectCount > maxRedirects) {
        throw new RedirectOverflowException(
            format("Refusing to follow more than %s redirects", maxRedirects));
      }

      List<Record> records = response.getRecords();
      if (records.isEmpty()) {
        future.complete(response);
      } else if (records.get(0).getType() == DNAME || records.get(0).getType() == CNAME) {
        lookupWithCache(
                buildRedirectQuery(response, query),
                makeAliases(response.getAliases(), query.getName()))
            .thenAccept(m -> maybeFollowRedirect(m, query, redirectCount + 1, future));
      } else {
        future.complete(response);
      }
    } catch (LookupFailedException e) {
      future.completeExceptionally(e);
    }
  }

  /** Return an unmodifiable list containing the contents of previous, if any, plus name. */
  private List<Name> makeAliases(List<Name> previous, Name name) {
    if (previous == null) {
      return singletonList(name);
    } else {
      List<Name> copy = new ArrayList<>(previous);
      copy.add(name);
      return Collections.unmodifiableList(copy);
    }
  }

  private Record buildRedirectQuery(LookupResult response, Record question) {
    List<Record> answer = response.getRecords();
    Record firstAnswer = answer.get(0);
    if (answer.size() != 1) {
      throw new InvalidZoneDataException("Multiple CNAME RRs not allowed, SEE RFC1034 3.6.2");
    }

    if (firstAnswer.getType() == CNAME) {
      return Record.newRecord(
          ((CNAMERecord) firstAnswer).getTarget(), question.getType(), question.getDClass());
    }
    // if it is not a CNAME, it's a DNAME
    try {
      Name name = question.getName().fromDNAME((DNAMERecord) firstAnswer);
      return Record.newRecord(name, question.getType(), question.getDClass());
    } catch (NameTooLongException e) {
      throw new InvalidZoneDataException(
          "DNAME redirect would result in a name that would be too long");
    }
  }

  /** Returns a LookupResult if this response was a non-exceptional empty result, else null. */
  private static LookupResult buildResult(Message answer, List<Name> aliases) {
    int rcode = answer.getRcode();
    List<Record> answerRecords = answer.getSection(Section.ANSWER);
    if (answerRecords.isEmpty()) {
      switch (rcode) {
        case Rcode.NXDOMAIN:
          throw new NoSuchDomainException();
        case Rcode.NXRRSET:
          throw new NoSuchRRSetException();
        case Rcode.SERVFAIL:
          throw new ServerFailedException();
        default:
          throw new LookupFailedException(
              format("Unknown non-success error code %s", Rcode.string(rcode)));
      }
    }
    return new LookupResult(answerRecords, aliases);
  }
}

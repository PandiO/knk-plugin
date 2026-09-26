package net.knightsandkings.knk.core.dataaccess;

import net.knightsandkings.knk.core.domain.users.TitleBracket;
import net.knightsandkings.knk.core.ports.api.TitleBracketsQueryApi;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Cached title-bracket list (InventoryMenu content port CP3/CP8). Brackets are seeded data that
 * practically never change, so the whole list is cached for {@code ttl} ({@link CachedList}:
 * completed future while fresh, one shared in-flight request, stale list on failure).
 */
public class TitleBracketsDataAccess {

    private final CachedList<TitleBracket> list;

    public TitleBracketsDataAccess(TitleBracketsQueryApi queryApi, Duration ttl) {
        this(queryApi, ttl, Clock.systemUTC());
    }

    public TitleBracketsDataAccess(TitleBracketsQueryApi queryApi, Duration ttl, Clock clock) {
        this.list = new CachedList<>(() -> queryApi.listAll().thenApply(brackets -> brackets == null ? List.of()
                : brackets.stream().sorted(Comparator.comparingInt(TitleBracket::minExperience)).toList()), ttl, clock);
    }

    /** Every bracket, lowest {@code minExperience} first. */
    public CompletableFuture<List<TitleBracket>> listAsync() {
        return list.getAsync();
    }

    /** The cached list if one was ever fetched (possibly stale), else an empty list. Never does I/O. */
    public List<TitleBracket> cachedOrEmpty() {
        return list.cachedOrEmpty();
    }

    public void invalidate() {
        list.invalidate();
    }
}

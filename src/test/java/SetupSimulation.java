import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import static java.time.Duration.ofSeconds;

/**
 * Засевает данные перед нагрузочными тестами.
 * <p>
 * Временная шкала:
 * t=0s:   создание 10 seed-авторов + 1 celebrity + 10 000 пользователей (200/s × 50s)
 * t=60s:  10 000 пользователей подписываются на seed-авторов (ramp 20s)
 * t=84s:  5 001 пользователь подписывается на celebrity (ramp 10s)
 * t=95s:  ожидание propagation UserFollowedEvents (300s — outbox drain + FanoutService Redis)
 * t=400s: 100 постов от seed-авторов
 * t=405s: ожидание propagation PostCreatedEvents (120s)
 * t=525s: after() — запись CSV feeders
 * <p>
 * Запуск:
 * mvn gatling:test -Dgatling.simulationClass=SetupSimulation \
 * -DbaseUrlUser=http://<ip>:8080 -DbaseUrlPost=http://<ip>:8081
 */
public class SetupSimulation extends Simulation {

    static final CopyOnWriteArrayList<String> normalUserIds = new CopyOnWriteArrayList<>();
    static final CopyOnWriteArrayList<String> seedAuthorIds = new CopyOnWriteArrayList<>();
    static final CopyOnWriteArrayList<String> postIds = new CopyOnWriteArrayList<>();
    static volatile String celebrityId = null;

    static final AtomicInteger followIdx = new AtomicInteger(0);
    static final AtomicInteger celebFollowIdx = new AtomicInteger(0);
    static final AtomicInteger postCounter = new AtomicInteger(0);

    final String baseUrlUser = System.getProperty("baseUrlUser", "http://localhost:8080");
    final String baseUrlPost = System.getProperty("baseUrlPost", "http://localhost:8081");

    HttpProtocolBuilder httpProtocol = http.shareConnections();

    // ── Phase 1a: 10 seed authors ────────────────────────────────────────────
    ScenarioBuilder createSeedAuthors = scenario("Create seed authors")
            .exec(
                    http("POST /users - seed")
                            .post(baseUrlUser + "/api/v1/users")
                            .header("Content-Type", "application/json")
                            .body(StringBody(session ->
                                    "{\"username\":\"author_" + shortUuid()
                                            + "\",\"email\":\"author_" + shortUuid() + "@test.com\"}"))
                            .check(status().is(201))
                            .check(jsonPath("$").saveAs("userId"))
            )
            .exec(session -> {
                seedAuthorIds.add(session.getString("userId"));
                return session;
            });

    // ── Phase 1b: 1 celebrity ─────────────────────────────────────────────────
    ScenarioBuilder createCelebrity = scenario("Create celebrity")
            .exec(
                    http("POST /users - celebrity")
                            .post(baseUrlUser + "/api/v1/users")
                            .header("Content-Type", "application/json")
                            .body(StringBody("{\"username\":\"celebrity_lt\",\"email\":\"celebrity_lt@test.com\"}"))
                            .check(status().is(201))
                            .check(jsonPath("$").saveAs("userId"))
            )
            .exec(session -> {
                celebrityId = session.getString("userId");
                return session;
            });

    // ── Phase 1c: 10 000 regular users ────────────────────────────────────────
    ScenarioBuilder createNormalUsers = scenario("Create normal users")
            .exec(
                    http("POST /users - normal")
                            .post(baseUrlUser + "/api/v1/users")
                            .header("Content-Type", "application/json")
                            .body(StringBody(session ->
                                    "{\"username\":\"user_" + shortUuid()
                                            + "\",\"email\":\"user_" + shortUuid() + "@test.com\"}"))
                            .check(status().is(201))
                            .check(jsonPath("$").saveAs("userId"))
            )
            .exec(session -> {
                normalUserIds.add(session.getString("userId"));
                return session;
            });

    // ── Phase 2a: each normal user follows 1 random seed author ───────────────
    // Starts at t=60s (10s buffer after all 10 000 users created at t≈50s)
    ScenarioBuilder followSeedAuthors = scenario("Follow seed authors")
            .exec(session -> {
                int size = normalUserIds.size();
                int idx = size > 0 ? (followIdx.getAndIncrement() % size) : 0;
                int authorIdx = ThreadLocalRandom.current().nextInt(Math.max(1, seedAuthorIds.size()));
                return session
                        .set("followerId", normalUserIds.get(idx))
                        .set("authorId", seedAuthorIds.get(authorIdx));
            })
            .exec(
                    http("POST /followings - normal → seed")
                            .post(session -> baseUrlUser + "/api/v1/users/"
                                    + session.getString("followerId")
                                    + "/followings/"
                                    + session.getString("authorId"))
                            .check(status().in(204, 400))
            );

    // ── Phase 2b: first 5 001 normal users follow celebrity ───────────────────
    // Starts at t=84s (4s buffer after followSeedAuthors ends at t≈80s)
    ScenarioBuilder followCelebrity = scenario("Follow celebrity")
            .exec(session -> {
                int size = normalUserIds.size();
                int idx = size > 0 ? (celebFollowIdx.getAndIncrement() % size) : 0;
                return session
                        .set("followerId", normalUserIds.get(idx))
                        .set("celebId", celebrityId);
            })
            .exec(
                    http("POST /followings - normal → celebrity")
                            .post(session -> baseUrlUser + "/api/v1/users/"
                                    + session.getString("followerId")
                                    + "/followings/"
                                    + session.getString("celebId"))
                            .check(status().in(204, 400))
            );

    // ── Phase 3: outbox wait after follows — 1 VU паузирует 300s ────────────
    // Outbox__Delay=10ms → ~150 events/s/replica × 3 replicas ≈ 450 events/s
    // 25k events (UserCreated + UserFollowed) дренируются за ~55s; 300s — запас 5×
    ScenarioBuilder waitFollowPropagation = scenario("Wait follow propagation")
            .pause(ofSeconds(300));

    // ── Phase 4: 100 posts by seed authors (10 per author) ────────────────────
    // Starts at t=157s
    ScenarioBuilder createPosts = scenario("Create posts")
            .exec(session -> {
                int idx = postCounter.getAndIncrement();
                return session.set("authorId", seedAuthorIds.get(idx % Math.max(1, seedAuthorIds.size())));
            })
            .exec(
                    http("POST /posts - seed")
                            .post(baseUrlPost + "/api/v1/posts")
                            .header("Content-Type", "application/json")
                            .body(StringBody(session ->
                                    "{\"authorId\":\"" + session.getString("authorId")
                                            + "\",\"title\":\"Seed post " + UUID.randomUUID()
                                            + "\",\"content\":\"NewsFeed load test seed content.\"}"))
                            .check(status().is(201))
                            .check(jsonPath("$").saveAs("postId"))
            )
            .exec(session -> {
                postIds.add(session.getString("postId"));
                return session;
            });

    // ── Phase 5: outbox wait after posts — 1 VU паузирует 120s ──────────────
    ScenarioBuilder waitPostPropagation = scenario("Wait post propagation")
            .pause(ofSeconds(120));

    {
        // andThen() отсутствует в Gatling 3.11.5 SetUp.
        // Последовательность имитируется через nothingFor() в injection-профилях.
        setUp(
                createSeedAuthors.injectOpen(atOnceUsers(10)),
                createCelebrity.injectOpen(atOnceUsers(1)),
                createNormalUsers.injectOpen(constantUsersPerSec(200).during(ofSeconds(50))),

                followSeedAuthors.injectOpen(
                        nothingFor(ofSeconds(60)),
                        rampUsers(10000).during(ofSeconds(20))),

                followCelebrity.injectOpen(
                        nothingFor(ofSeconds(84)),
                        rampUsers(5001).during(ofSeconds(10))),

                waitFollowPropagation.injectOpen(
                        nothingFor(ofSeconds(95)),
                        atOnceUsers(1)),

                createPosts.injectOpen(
                        nothingFor(ofSeconds(400)),
                        atOnceUsers(100)),

                waitPostPropagation.injectOpen(
                        nothingFor(ofSeconds(405)),
                        atOnceUsers(1))
        ).protocols(httpProtocol);
    }

    @Override
    public void after() {
        try {
            Path res = Path.of("src/test/resources");
            Files.createDirectories(res);

            Files.writeString(res.resolve("user-ids.csv"),
                    "userId\n" + String.join("\n", normalUserIds));
            Files.writeString(res.resolve("followee-ids.csv"),
                    "followeeId\n" + String.join("\n", normalUserIds));
            Files.writeString(res.resolve("post-ids.csv"),
                    "postId\n" + String.join("\n", postIds));
            if (celebrityId != null) {
                Files.writeString(res.resolve("celebrity-id.txt"), celebrityId);
            }

            System.out.printf(
                    "%n=== Setup complete ===%n  users: %d  posts: %d  celebrity: %s%n%n",
                    normalUserIds.size(), postIds.size(), celebrityId);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write feeder CSVs", e);
        }
    }

    private static String shortUuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}

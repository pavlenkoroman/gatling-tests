import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import static java.time.Duration.ofMinutes;

/**
 * Эксперимент 1 — пиковая нагрузка (~2 800 RPS суммарно).
 * <p>
 * Профиль каждого сценария: рамп rampMin мин → пик peakMin мин → рамп вниз rampMin мин.
 * <p>
 * Требует src/test/resources/: user-ids.csv, followee-ids.csv, post-ids.csv
 * <p>
 * Запуск:
 * mvn gatling:test -Dgatling.simulationClass=Experiment1PeakLoadSimulation \
 * -DbaseUrlFeed=http://<ip>:8083 \
 * -DbaseUrlUser=http://<ip>:8080 \
 * -DbaseUrlPost=http://<ip>:8081
 */
public class Experiment1PeakLoadSimulation extends Simulation {

    final String baseUrlFeed = System.getProperty("baseUrlFeed", "http://localhost:32402");
    final String baseUrlUser = System.getProperty("baseUrlUser", "http://localhost:32400");
    final String baseUrlPost = System.getProperty("baseUrlPost", "http://localhost:32401");

    final int rampMin = Integer.parseInt(System.getProperty("rampMin", "5"));
    final int peakMin = Integer.parseInt(System.getProperty("peakMin", "20"));

    HttpProtocolBuilder httpProtocol = http.shareConnections();

    FeederBuilder<String> userFeeder = csv("user-ids.csv").circular();
    FeederBuilder<String> followeeFeeder = csv("followee-ids.csv").random();
    FeederBuilder<String> postFeeder = csv("post-ids.csv").circular();

    // GET /feed — 5 000 RPS
    ScenarioBuilder getFeed = scenario("GET /feed")
            .feed(userFeeder)
            .exec(
                    http("GET /feed")
                            .get(baseUrlFeed + "/api/v1/me/feed?pageSize=20")
                            .header("X-User-Id", "#{userId}")
                            .check(status().is(200))
            );

    // GET /users/{id} — 2 500 RPS
    ScenarioBuilder getUser = scenario("GET /users/{id}")
            .feed(userFeeder)
            .exec(
                    http("GET /users/{id}")
                            .get(session -> baseUrlUser + "/api/v1/users/" + session.getString("userId"))
                            .check(status().is(200))
            );

    // GET /posts/{id} — 1 300 RPS
    ScenarioBuilder getPost = scenario("GET /posts/{id}")
            .feed(postFeeder)
            .exec(
                    http("GET /posts/{id}")
                            .get(session -> baseUrlPost + "/api/v1/posts/" + session.getString("postId"))
                            .check(status().is(200))
            );

    // POST /posts — 25 RPS
    ScenarioBuilder createPost = scenario("POST /posts")
            .feed(userFeeder)
            .exec(
                    http("POST /posts")
                            .post(baseUrlPost + "/api/v1/posts")
                            .header("Content-Type", "application/json")
                            .body(StringBody(session ->
                                    "{\"authorId\":\"" + session.getString("userId")
                                            + "\",\"title\":\"Load test post\",\"content\":\"Experiment 1 write traffic.\"}"))
                            .check(status().is(201))
            );

    // POST /followings — 10 RPS (случайные пары из двух независимых feeders)
    ScenarioBuilder followOps = scenario("POST /followings")
            .feed(userFeeder)
            .feed(followeeFeeder)
            .exec(
                    http("POST /followings")
                            .post(session -> baseUrlUser + "/api/v1/users/"
                                    + session.getString("userId")
                                    + "/followings/"
                                    + session.getString("followeeId"))
                            .check(status().in(204, 400))
            );

    {
        setUp(
                getFeed.injectOpen(
                        rampUsersPerSec(0).to(1500).during(ofMinutes(rampMin)),
                        constantUsersPerSec(1500).during(ofMinutes(peakMin)),
                        rampUsersPerSec(1500).to(0).during(ofMinutes(rampMin))
                ),
                getUser.injectOpen(
                        rampUsersPerSec(0).to(800).during(ofMinutes(rampMin)),
                        constantUsersPerSec(800).during(ofMinutes(peakMin)),
                        rampUsersPerSec(800).to(0).during(ofMinutes(rampMin))
                ),
                getPost.injectOpen(
                        rampUsersPerSec(0).to(500).during(ofMinutes(rampMin)),
                        constantUsersPerSec(500).during(ofMinutes(peakMin)),
                        rampUsersPerSec(500).to(0).during(ofMinutes(rampMin))
                ),
                createPost.injectOpen(
                        rampUsersPerSec(0).to(15).during(ofMinutes(rampMin)),
                        constantUsersPerSec(15).during(ofMinutes(peakMin)),
                        rampUsersPerSec(15).to(0).during(ofMinutes(rampMin))
                ),
                followOps.injectOpen(
                        rampUsersPerSec(0).to(5).during(ofMinutes(rampMin)),
                        constantUsersPerSec(5).during(ofMinutes(peakMin)),
                        rampUsersPerSec(5).to(0).during(ofMinutes(rampMin))
                )
        ).protocols(httpProtocol);
    }
}

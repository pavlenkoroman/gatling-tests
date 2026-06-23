import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.io.IOException;
import java.nio.file.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import static java.time.Duration.ofMinutes;
import static java.time.Duration.ofSeconds;

/**
 * Эксперимент 3 — celebrity threshold (1 500 RPS, 8 минут).
 *
 * Структура:
 *   - 1 500 RPS GET /feed всё время
 *   - t=2min: celebrity публикует пост → FanoutService должен НЕ делать fanout
 *             (у celebrity >= 5 001 подписчик > threshold 5 000)
 *   - t=5min: обычный пользователь публикует пост → обычный fanout
 *
 * Метрики для диплома:
 *   - Latency GET /feed до/после celebrity-поста (должна остаться стабильной)
 *   - Kafka consumer lag (не должен расти)
 *   - FanoutService: log "Skipping fanout" для celebrity-поста
 *
 * Запуск:
 *   mvn gatling:test -Dgatling.simulationClass=Experiment3CelebritySimulation \
 *       -DbaseUrlFeed=http://<ip>:8083 \
 *       -DbaseUrlPost=http://<ip>:8081 \
 *       -DbaseUrlUser=http://<ip>:8080
 */
public class Experiment3CelebritySimulation extends Simulation {

    final String baseUrlFeed = System.getProperty("baseUrlFeed", "http://localhost:8083");
    final String baseUrlPost = System.getProperty("baseUrlPost", "http://localhost:8081");
    final String baseUrlUser = System.getProperty("baseUrlUser", "http://localhost:8080");

    HttpProtocolBuilder httpProtocol = http.shareConnections();

    final String celebrityId;
    final String regularUserId;

    {
        try {
            celebrityId = Files.readString(
                Path.of("src/test/resources/celebrity-id.txt")).trim();
        } catch (IOException e) {
            throw new RuntimeException(
                "celebrity-id.txt not found. Run SetupSimulation first.", e);
        }
        try {
            // Берём первую строку после заголовка из user-ids.csv как "обычного" пользователя
            regularUserId = Files.readAllLines(
                Path.of("src/test/resources/user-ids.csv")).get(1).trim();
        } catch (IOException e) {
            throw new RuntimeException(
                "user-ids.csv not found. Run SetupSimulation first.", e);
        }
    }

    FeederBuilder<String> userFeeder = csv("user-ids.csv").circular();

    // Фоновая нагрузка: 2 000 RPS GET /feed
    ScenarioBuilder getFeed = scenario("GET /feed")
        .feed(userFeeder)
        .exec(
            http("GET /feed")
                .get(baseUrlFeed + "/api/v1/me/feed?pageSize=20")
                .header("X-User-Id", "#{userId}")
                .check(status().is(200))
        );

    // t=2min: celebrity публикует пост
    ScenarioBuilder celebrityPost = scenario("POST celebrity post")
        .exec(
            http("POST /posts - celebrity")
                .post(baseUrlPost + "/api/v1/posts")
                .header("Content-Type", "application/json")
                .body(StringBody(
                    "{\"authorId\":\"" + celebrityId
                    + "\",\"title\":\"Celebrity announcement\",\"content\":\"Breaking news from celebrity.\"}"))
                .check(status().is(201))
        );

    // t=5min: обычный пользователь публикует пост (control: обычный fanout)
    ScenarioBuilder regularPost = scenario("POST regular post")
        .exec(
            http("POST /posts - regular")
                .post(baseUrlPost + "/api/v1/posts")
                .header("Content-Type", "application/json")
                .body(StringBody(
                    "{\"authorId\":\"" + regularUserId
                    + "\",\"title\":\"Regular post\",\"content\":\"Normal fanout control post.\"}"))
                .check(status().is(201))
        );

    {
        setUp(
            getFeed      .injectOpen(constantUsersPerSec(1500).during(ofMinutes(8))),
            celebrityPost.injectOpen(nothingFor(ofMinutes(2)), atOnceUsers(1)),
            regularPost  .injectOpen(nothingFor(ofMinutes(5)), atOnceUsers(1))
        ).protocols(httpProtocol);
    }
}

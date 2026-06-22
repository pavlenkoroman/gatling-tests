import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;
import static java.time.Duration.ofMinutes;

/**
 * Эксперимент 2 — деградация при отказе Redis (500 RPS, 15 минут).
 * <p>
 * Redis убивается и поднимается ВРУЧНУЮ по SSH во время прогона:
 * docker compose stop redis   # ~t=3min
 * docker compose start redis  # ~t=8min
 * <p>
 * Gatling только генерирует нагрузку; деградацию и восстановление
 * смотреть на Grafana (redis-exporter + FeedService latency).
 * <p>
 * Запуск:
 * mvn gatling:test -Dgatling.simulationClass=Experiment2RedisFailoverSimulation \
 * -DbaseUrlFeed=http://<ip>:8083
 */
public class Experiment2RedisFailoverSimulation extends Simulation {

    final String baseUrlFeed = System.getProperty("baseUrlFeed", "http://localhost:8083");
    final int durationMin = Integer.parseInt(System.getProperty("durationMin", "15"));

    HttpProtocolBuilder httpProtocol = http
            .baseUrl(baseUrlFeed)
            .shareConnections();

    FeederBuilder<String> userFeeder = csv("user-ids.csv").circular();

    ScenarioBuilder getFeed = scenario("GET /feed")
            .feed(userFeeder)
            .exec(
                    http("GET /feed")
                            .get("/api/v1/me/feed?pageSize=20")
                            .header("X-User-Id", "#{userId}")
                            .check(status().is(200))
            );

    {
        setUp(
                getFeed.injectOpen(constantUsersPerSec(500).during(ofMinutes(durationMin)))
        ).protocols(httpProtocol);
    }
}

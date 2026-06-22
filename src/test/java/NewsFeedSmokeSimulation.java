import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import java.util.UUID;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class NewsFeedSmokeSimulation extends Simulation {

    private static final String USER_URL = System.getProperty("baseUrlUser", "http://localhost:32400");
    private static final String POST_URL = System.getProperty("baseUrlPost", "http://localhost:32401");
    private static final String FEED_URL = System.getProperty("baseUrlFeed", "http://localhost:32402");

    // --- Happy path: создать пользователей, подписаться, загрузить картинку,
    //                 создать пост, прочитать ленту ---
    ScenarioBuilder happyPath = scenario("Happy path")

        .exec(session -> session
            .set("emailA", "user_a_" + UUID.randomUUID() + "@test.com")
            .set("usernameA", "user_a_" + UUID.randomUUID())
            .set("emailB", "user_b_" + UUID.randomUUID() + "@test.com")
            .set("usernameB", "user_b_" + UUID.randomUUID())
        )

        // 1. Создать пользователя A
        .exec(
            http("POST /users — create user A")
                .post(USER_URL + "/api/v1/users")
                .header("Content-Type", "application/json")
                .body(StringBody("""
                    {"username": "#{usernameA}", "email": "#{emailA}"}
                    """))
                .check(status().is(201))
                .check(jsonPath("$").saveAs("userAId"))
        )

        // 2. Создать пользователя B
        .exec(
            http("POST /users — create user B")
                .post(USER_URL + "/api/v1/users")
                .header("Content-Type", "application/json")
                .body(StringBody("""
                    {"username": "#{usernameB}", "email": "#{emailB}"}
                    """))
                .check(status().is(201))
                .check(jsonPath("$").saveAs("userBId"))
        )

        // 3. A подписывается на B
        .exec(
            http("POST /followings — A follows B")
                .post(USER_URL + "/api/v1/users/#{userAId}/followings/#{userBId}")
                .check(status().is(204))
        )

        // 4. Пауза — ждём пока UserFollowedEvent дойдёт через outbox → Kafka → FanoutService → Redis
        .pause(5)

        // 5. Загрузить картинку (файл test-image.jpg из src/test/resources/)
        .exec(
            http("POST /posts/media — upload image")
                .post(POST_URL + "/api/v1/posts/media")
                .formUpload("file", "test-image.jpg")
                .check(status().is(200))
                .check(jsonPath("$").saveAs("mediaUrl"))
        )

        // 6. B публикует пост с картинкой
        .exec(
            http("POST /posts — create post with media")
                .post(POST_URL + "/api/v1/posts")
                .header("Content-Type", "application/json")
                .body(StringBody(session -> """
                    {
                      "authorId": "%s",
                      "title": "Post with image",
                      "content": "Check out this photo",
                      "mediaUrls": ["%s"],
                      "tags": ["smoke"]
                    }
                    """.formatted(
                        session.getString("userBId"),
                        session.getString("mediaUrl")
                    )
                ))
                .check(status().is(201))
        )

        // 7. Пауза — ждём пока outbox воркер (1s) + Kafka + FanoutService обработают PostCreated
        .pause(7)

        // 8. A читает ленту — должен увидеть пост B
        .exec(
            http("GET /feed — A reads feed")
                .get(FEED_URL + "/api/v1/me/feed?pageSize=20")
                .header("X-User-Id", "#{userAId}")
                .check(status().is(200))
                .check(jsonPath("$.posts[0]").exists())
                .check(jsonPath("$.posts[0].author.id").isEL("#{userBId}"))
        )

        // 9. A отписывается от B
        .exec(
            http("DELETE /followings — A unfollows B")
                .delete(USER_URL + "/api/v1/users/#{userAId}/followings/#{userBId}")
                .check(status().is(204))
        );

    // --- Валидационные кейсы ---
    ScenarioBuilder validations = scenario("Validations")

        // Некорректный email
        .exec(
            http("POST /users — invalid email → 400")
                .post(USER_URL + "/api/v1/users")
                .header("Content-Type", "application/json")
                .body(StringBody("""
                    {"username": "bad_user", "email": "not-an-email"}
                    """))
                .check(status().is(400))
        )

        // Пустой заголовок поста
        .exec(
            http("POST /posts — empty title → 400")
                .post(POST_URL + "/api/v1/posts")
                .header("Content-Type", "application/json")
                .body(StringBody("""
                    {"authorId": "00000000-0000-0000-0000-000000000001", "title": "", "content": "Some content"}
                    """))
                .check(status().is(400))
        )

        // Пустое содержимое поста
        .exec(
            http("POST /posts — empty content → 400")
                .post(POST_URL + "/api/v1/posts")
                .header("Content-Type", "application/json")
                .body(StringBody("""
                    {"authorId": "00000000-0000-0000-0000-000000000001", "title": "Valid title", "content": ""}
                    """))
                .check(status().is(400))
        )

        // Подписка на самого себя — no-op, домен тихо игнорирует
        .exec(
            http("POST /followings — self-follow → 204")
                .post(USER_URL + "/api/v1/users/00000000-0000-0000-0000-000000000001/followings/00000000-0000-0000-0000-000000000001")
                .check(status().is(204))
        )

        // Лента без X-User-Id заголовка
        .exec(
            http("GET /feed — missing X-User-Id → 400")
                .get(FEED_URL + "/api/v1/me/feed?pageSize=20")
                .check(status().is(400))
        );

    {
        setUp(
            happyPath.injectOpen(atOnceUsers(1)),
            validations.injectOpen(atOnceUsers(1))
        );
    }
}

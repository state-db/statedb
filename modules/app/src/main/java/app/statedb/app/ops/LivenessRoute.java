package app.statedb.app.ops;

import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Public liveness for load balancers that can only reach port 8080. It runs on the event loop and
 * never touches the database, so a task busy with Pulumi writes never looks dead.
 */
@ApplicationScoped
public class LivenessRoute {

  void init(@Observes Router router) {
    router.get("/health/live").handler(this::live);
  }

  private void live(RoutingContext rc) {
    rc.response().putHeader("Content-Type", "text/plain").end("ok");
  }
}

package app.statedb.testsupport;

import io.quarkus.narayana.jta.QuarkusTransaction;
import java.util.concurrent.Callable;

public final class TestTransactions {
  private TestTransactions() {}

  public static <T> T read(Callable<T> work) {
    return QuarkusTransaction.requiringNew().call(work);
  }

  public static void tx(Runnable work) {
    QuarkusTransaction.requiringNew().run(work);
  }
}

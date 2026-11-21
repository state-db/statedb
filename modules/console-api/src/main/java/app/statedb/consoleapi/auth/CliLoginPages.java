package app.statedb.consoleapi.auth;

/** Static HTML fragments for the CLI-login approval flow. */
final class CliLoginPages {

  private CliLoginPages() {}

  static String cancelledPage() {
    return """
        <!doctype html>
        <html lang="en">
          <head><meta charset="utf-8" /><title>CLI login cancelled</title></head>
          <body><h1>CLI login cancelled.</h1><p>No token was created.</p></body>
        </html>
        """;
  }

  static String signInIncompletePage() {
    return """
        <!doctype html>
        <html lang="en">
          <head><meta charset="utf-8" /><title>CLI login not completed</title></head>
          <body>
            <h1>Sign-in was not completed.</h1>
            <p>No token was created. Run <code>pulumi login</code> again to retry.</p>
          </body>
        </html>
        """;
  }
}

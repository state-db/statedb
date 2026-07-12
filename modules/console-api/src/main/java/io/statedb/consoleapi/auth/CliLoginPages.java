package io.statedb.consoleapi.auth;

/**
 * The two HTML pages of the CLI-login approval flow. Pure templating — all values are HTML-escaped
 * here; callers pass raw strings.
 */
final class CliLoginPages {

  private CliLoginPages() {}

  static String approvalPage(
      String signedInAs, String clientLabel, int port, String signedRequest) {
    return """
        <!doctype html>
        <html lang="en">
          <head>
            <meta charset="utf-8" />
            <meta name="referrer" content="no-referrer" />
            <title>Allow CLI access</title>
            <style>
              body { font-family: system-ui, sans-serif; margin: 3rem auto; max-width: 40rem; line-height: 1.45; }
              dl { display: grid; grid-template-columns: 9rem 1fr; gap: .5rem 1rem; }
              dt { color: #555; }
              dd { margin: 0; }
              button { padding: .65rem 1rem; margin-right: .5rem; }
            </style>
          </head>
          <body>
            <h1>Allow CLI access?</h1>
            <p>Pulumi CLI is requesting a 24-hour state-db token for your account.</p>
            <dl>
              <dt>Signed in as</dt><dd>%s</dd>
              <dt>Client</dt><dd>%s</dd>
              <dt>Access</dt><dd>Organizations you currently belong to</dd>
              <dt>Loopback</dt><dd>127.0.0.1:%d</dd>
            </dl>
            <form method="post" action="/api/auth/cli-login/approve">
              <input type="hidden" name="request" value="%s" />
              <button type="submit" name="decision" value="allow">Allow access</button>
              <button type="submit" name="decision" value="cancel">Cancel</button>
            </form>
          </body>
        </html>
        """
        .formatted(escape(signedInAs), escape(clientLabel), port, escape(signedRequest));
  }

  static String cancelledPage() {
    return """
        <!doctype html>
        <html lang="en">
          <head><meta charset="utf-8" /><title>CLI login cancelled</title></head>
          <body><h1>CLI login cancelled.</h1><p>No token was created.</p></body>
        </html>
        """;
  }

  static String escape(String s) {
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}

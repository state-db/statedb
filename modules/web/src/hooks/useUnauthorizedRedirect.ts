import { useEffect } from "react";

import { redirectToLogin, UnauthorizedError } from "@/lib/api";

/**
 * Sends the browser to the login screen when a query failed with
 * UnauthorizedError. Returns whether the error is an UnauthorizedError so
 * callers can render nothing while the redirect happens.
 */
export function useUnauthorizedRedirect(error: unknown): boolean {
  const unauthorized = error instanceof UnauthorizedError;
  useEffect(() => {
    if (unauthorized) {
      redirectToLogin();
    }
  }, [unauthorized]);
  return unauthorized;
}

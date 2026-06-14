import { useQuery } from "@tanstack/react-query";

import { api, UnauthorizedError } from "@/lib/api";

export function useCurrentUser() {
  return useQuery({
    queryKey: ["current-user"],
    queryFn: () => api.user.get(),
    retry: (failureCount, error) =>
      !(error instanceof UnauthorizedError) && failureCount < 2,
  });
}

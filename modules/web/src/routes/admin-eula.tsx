import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, ShieldAlert } from "lucide-react";
import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { toast } from "sonner";

import { ErrorAlert } from "@/components/console/ErrorAlert";
import { Timestamp } from "@/components/console/Timestamp";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Skeleton } from "@/components/ui/skeleton";
import { useUnauthorizedRedirect } from "@/hooks/useUnauthorizedRedirect";
import { api, type Eula, UnauthorizedError } from "@/lib/api";

export const EULA_KEY = ["admin", "eula"] as const;

export function AdminLicensePage() {
  const queryClient = useQueryClient();
  const eula = useQuery({
    queryKey: EULA_KEY,
    queryFn: api.admin.eula.get,
    retry: (count, error) => !(error instanceof UnauthorizedError) && count < 2,
  });
  const accept = useMutation({
    mutationFn: (version: string) => api.admin.eula.accept(version),
    onSuccess: (accepted) => {
      queryClient.setQueryData(EULA_KEY, accepted);
      toast.success("License agreement accepted");
    },
    onError: (error: Error) => toast.error(error.message),
  });
  const unauthorized = useUnauthorizedRedirect(eula.error);

  if (unauthorized) return null;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h1 className="text-2xl font-semibold tracking-tight">License</h1>
        <p className="text-sm text-muted-foreground">
          Review and accept the End User License Agreement for this deployment.
        </p>
      </div>

      {eula.error ? <ErrorAlert error={eula.error} /> : null}

      {eula.isPending ? (
        <Skeleton className="h-96 w-full" />
      ) : eula.data ? (
        <>
          {eula.data.accepted ? (
            <Alert>
              <CheckCircle2 className="size-4" />
              <AlertTitle>Version {eula.data.version} accepted</AlertTitle>
              <AlertDescription>
                Accepted <Timestamp value={eula.data.acceptedAt ?? ""} />
                {eula.data.acceptedVia === "configuration"
                  ? " by this deployment's configuration"
                  : " in the console"}
                . Stack creation is enabled for this deployment.
              </AlertDescription>
            </Alert>
          ) : (
            <Alert variant="destructive">
              <ShieldAlert className="size-4" />
              <AlertTitle>EULA acceptance required</AlertTitle>
              <AlertDescription>
                The EULA must be accepted to enable full functionality for this deployment.
                Review version {eula.data.version} below.
              </AlertDescription>
            </Alert>
          )}

          <EulaAgreementCard
            key={eula.data.documentSha256}
            eula={eula.data}
            acceptPending={accept.isPending}
            onAccept={(version) => accept.mutate(version)}
          />
        </>
      ) : null}
    </div>
  );
}

function EulaAgreementCard({
  eula,
  acceptPending,
  onAccept,
}: {
  eula: Eula;
  acceptPending: boolean;
  onAccept: (version: string) => void;
}) {
  const [confirmed, setConfirmed] = useState(false);
  const [readToEnd, setReadToEnd] = useState(false);
  const eulaTextRef = useRef<HTMLDivElement | null>(null);
  const canConfirm = eula.accepted || readToEnd;

  const updateReadToEnd = useCallback(() => {
    const element = eulaTextRef.current;
    if (!element) return;

    const scrollRemaining = element.scrollHeight - element.scrollTop - element.clientHeight;
    if (scrollRemaining <= 2) {
      setReadToEnd(true);
    }
  }, []);

  useEffect(() => {
    const frame = window.requestAnimationFrame(updateReadToEnd);
    return () => window.cancelAnimationFrame(frame);
  }, [updateReadToEnd]);

  return (
    <Card>
      <CardHeader>
        <CardTitle>statedb End User License Agreement</CardTitle>
        <CardDescription>
          Version {eula.version} | SHA-256 {eula.documentSha256.slice(0, 16)}...
        </CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-6">
        <div
          ref={eulaTextRef}
          className="max-h-[32rem] overflow-y-auto rounded-md border bg-background p-5"
          data-testid="eula-text"
          onScroll={updateReadToEnd}
        >
          <MarkdownDocument text={eula.text} />
        </div>

        {eula.accepted ? null : (
          <div className="flex flex-col gap-4">
            <label className="flex items-start gap-3 text-sm">
              <Checkbox
                checked={confirmed}
                disabled={!canConfirm}
                onCheckedChange={(checked) => setConfirmed(checked === true)}
                aria-label="Confirm authority to accept"
              />
              <span className="flex flex-col gap-1 text-muted-foreground">
                <span>
                  I have read the agreement and I am authorized to accept it on behalf of the
                  organization operating this deployment.
                </span>
                {!canConfirm ? (
                  <span className="text-xs">
                    Scroll to the end of the agreement to enable this checkbox.
                  </span>
                ) : null}
              </span>
            </label>
            <div>
              <Button disabled={!confirmed || acceptPending} onClick={() => onAccept(eula.version)}>
                {acceptPending ? "Accepting..." : "Accept agreement"}
              </Button>
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function MarkdownDocument({ text }: { text: string }) {
  const blocks = parseMarkdownBlocks(text);

  return (
    <article className="max-w-none text-sm leading-6 text-foreground">
      {blocks.map((block, index) => {
        if (block.kind === "heading") {
          const Tag = block.level === 1 ? "h2" : block.level === 2 ? "h3" : "h4";
          return (
            <Tag
              key={index}
              className={
                block.level === 1
                  ? "mb-4 text-xl font-semibold tracking-tight"
                  : block.level === 2
                    ? "mb-2 mt-6 text-base font-semibold tracking-tight"
                    : "mb-2 mt-4 text-sm font-semibold tracking-tight"
              }
            >
              {renderInlineMarkdown(block.text)}
            </Tag>
          );
        }

        if (block.kind === "list") {
          return (
            <ul key={index} className="my-3 list-disc space-y-2 pl-5 text-muted-foreground">
              {block.items.map((item, itemIndex) => (
                <li key={itemIndex}>{renderInlineMarkdown(item)}</li>
              ))}
            </ul>
          );
        }

        return (
          <p key={index} className="my-3 text-muted-foreground">
            {renderInlineMarkdown(block.text)}
          </p>
        );
      })}
    </article>
  );
}

type MarkdownBlock =
  | { kind: "heading"; level: 1 | 2 | 3; text: string }
  | { kind: "list"; items: string[] }
  | { kind: "paragraph"; text: string };

function parseMarkdownBlocks(text: string): MarkdownBlock[] {
  const blocks: MarkdownBlock[] = [];
  let paragraph: string[] = [];
  let list: string[] = [];

  const flushParagraph = () => {
    if (paragraph.length === 0) return;
    blocks.push({ kind: "paragraph", text: paragraph.join(" ") });
    paragraph = [];
  };
  const flushList = () => {
    if (list.length === 0) return;
    blocks.push({ kind: "list", items: list });
    list = [];
  };

  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trimEnd();
    if (!line.trim()) {
      flushParagraph();
      flushList();
      continue;
    }

    const heading = /^(#{1,3})\s+(.+)$/.exec(line);
    if (heading) {
      flushParagraph();
      flushList();
      blocks.push({
        kind: "heading",
        level: heading[1].length as 1 | 2 | 3,
        text: heading[2],
      });
      continue;
    }

    const bullet = /^-\s+(.+)$/.exec(line);
    if (bullet) {
      flushParagraph();
      list.push(bullet[1]);
      continue;
    }

    if (/^\s+/.test(rawLine) && list.length > 0) {
      list[list.length - 1] = `${list[list.length - 1]} ${line.trim()}`;
      continue;
    }

    flushList();
    paragraph.push(line.trim());
  }

  flushParagraph();
  flushList();
  return blocks;
}

function renderInlineMarkdown(text: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  const tokens = /(`[^`]+`|\*\*[^*]+\*\*|\*[^*]+\*)/g;
  let lastIndex = 0;

  for (const match of text.matchAll(tokens)) {
    if (match.index > lastIndex) {
      nodes.push(text.slice(lastIndex, match.index));
    }

    const token = match[0];
    const key = `${match.index}-${token}`;
    if (token.startsWith("`")) {
      nodes.push(
        <code key={key} className="rounded bg-muted px-1 py-0.5 font-mono text-xs">
          {token.slice(1, -1)}
        </code>,
      );
    } else if (token.startsWith("**")) {
      nodes.push(<strong key={key}>{token.slice(2, -2)}</strong>);
    } else {
      nodes.push(<em key={key}>{token.slice(1, -1)}</em>);
    }
    lastIndex = match.index + token.length;
  }

  if (lastIndex < text.length) {
    nodes.push(text.slice(lastIndex));
  }
  return nodes;
}

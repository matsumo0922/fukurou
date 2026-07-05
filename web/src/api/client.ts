import type { paths } from "./openapi-types";

type OperationFor<
  Path extends keyof paths,
  Method extends keyof paths[Path],
> = paths[Path][Method];

type SuccessResponse<Operation> = Operation extends {
  responses: {
    200: infer Response;
  };
}
  ? Response
  : never;

type ResponseContent<Response> = Response extends {
  content: infer Content;
}
  ? Content
  : never;

type TextPayload<Content> = Content extends {
  "text/plain": infer Payload;
}
  ? Payload
  : never;

type JsonPayload<Content> = Content extends {
  "application/json": infer Payload;
}
  ? Payload
  : never;

type TextGetPath = {
  [Path in keyof paths]: "get" extends keyof paths[Path]
    ? TextPayload<ResponseContent<SuccessResponse<OperationFor<Path, "get">>>> extends never
      ? never
      : Path
    : never;
}[keyof paths];

type JsonGetPath = {
  [Path in keyof paths]: "get" extends keyof paths[Path]
    ? JsonPayload<ResponseContent<SuccessResponse<OperationFor<Path, "get">>>> extends never
      ? never
      : Path
    : never;
}[keyof paths];

type TextResponse<Path extends TextGetPath> = TextPayload<
  ResponseContent<SuccessResponse<OperationFor<Path, "get">>>
>;

type JsonResponse<Path extends JsonGetPath> = JsonPayload<
  ResponseContent<SuccessResponse<OperationFor<Path, "get">>>
>;

type ApiRequestInit = Omit<RequestInit, "body" | "method">;

export class ApiClientError extends Error {
  constructor(
    readonly path: string,
    readonly status: number,
    readonly responseText: string,
  ) {
    super(`GET ${path} failed with status ${status}`);
  }
}

export async function getText<Path extends TextGetPath>(
  path: Path,
  init: ApiRequestInit = {},
): Promise<TextResponse<Path>> {
  const response = await get(path, "text/plain", init);
  const text = await response.text();

  return text as TextResponse<Path>;
}

export async function getJson<Path extends JsonGetPath>(
  path: Path,
  init: ApiRequestInit = {},
): Promise<JsonResponse<Path>> {
  const response = await get(path, "application/json", init);

  return (await response.json()) as JsonResponse<Path>;
}

export function fetchRevision(): Promise<TextResponse<"/revision">> {
  return getText("/revision");
}

async function get(path: string, accept: string, init: ApiRequestInit): Promise<Response> {
  const headers = new Headers(init.headers);

  if (!headers.has("Accept")) {
    headers.set("Accept", accept);
  }

  const response = await fetch(path, {
    ...init,
    method: "GET",
    headers,
  });

  if (!response.ok) {
    throw new ApiClientError(path, response.status, await response.text());
  }

  return response;
}

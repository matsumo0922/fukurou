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
type ApiPathWithSearch<Path extends string> = Path | `${Path}?${string}`;

export type ApiResponse<Payload> = {
  status: number;
  data: Payload;
};

export class ApiClientError extends Error {
  constructor(
    readonly path: string,
    readonly status: number,
    readonly responseText: string,
  ) {
    super(`GET ${path} failed with status ${status}`);
  }
}

export async function getText<Path extends TextGetPath & string>(
  path: ApiPathWithSearch<Path>,
  init: ApiRequestInit = {},
): Promise<TextResponse<Path>> {
  return (await getTextResponse(path, [200], init)).data;
}

export async function getJson<Path extends JsonGetPath & string>(
  path: ApiPathWithSearch<Path>,
  init: ApiRequestInit = {},
): Promise<JsonResponse<Path>> {
  return (await getJsonResponse(path, [200], init)).data;
}

export function fetchRevision(): Promise<TextResponse<"/revision">> {
  return getText("/revision");
}

export async function getTextResponse<Path extends TextGetPath & string>(
  path: ApiPathWithSearch<Path>,
  allowedStatuses: readonly number[] = [200],
  init: ApiRequestInit = {},
): Promise<ApiResponse<TextResponse<Path>>> {
  const response = await get(path, "text/plain", allowedStatuses, init);

  return {
    status: response.status,
    data: (await response.text()) as TextResponse<Path>,
  };
}

export async function getJsonResponse<Path extends JsonGetPath & string>(
  path: ApiPathWithSearch<Path>,
  allowedStatuses: readonly number[] = [200],
  init: ApiRequestInit = {},
): Promise<ApiResponse<JsonResponse<Path>>> {
  const response = await get(path, "application/json", allowedStatuses, init);

  return {
    status: response.status,
    data: (await response.json()) as JsonResponse<Path>,
  };
}

async function get(
  path: string,
  accept: string,
  allowedStatuses: readonly number[],
  init: ApiRequestInit,
): Promise<Response> {
  const headers = new Headers(init.headers);

  if (!headers.has("Accept")) {
    headers.set("Accept", accept);
  }

  const response = await fetch(path, {
    ...init,
    method: "GET",
    headers,
  });

  if (!allowedStatuses.includes(response.status)) {
    throw new ApiClientError(path, response.status, await response.text());
  }

  return response;
}

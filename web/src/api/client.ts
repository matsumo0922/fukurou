import type { paths } from "./openapi-types";

type OperationFor<
  Path extends keyof paths,
  Method extends keyof paths[Path],
> = paths[Path][Method];

type OperationResponses<Operation> = Operation extends {
  responses: infer Responses;
}
  ? Responses
  : never;

type ResponseForStatus<Operation, Status extends number> = Status extends keyof OperationResponses<Operation>
  ? OperationResponses<Operation>[Status]
  : never;

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

type RequestBodyContent<Operation> = Operation extends {
  requestBody: {
    content: infer Content;
  };
}
  ? Content
  : never;

type JsonRequestBody<Content> = Content extends {
  "application/json": infer Payload;
}
  ? Payload
  : never;

type JsonPostRequestBody<Operation> = JsonRequestBody<RequestBodyContent<Operation>>;

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

type JsonPostPath = {
  [Path in keyof paths]: "post" extends keyof paths[Path]
    ? JsonPostRequestBody<OperationFor<Path, "post">> extends never
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

type JsonPostBody<Path extends JsonPostPath> = JsonPostRequestBody<OperationFor<Path, "post">>;

type JsonPostResponse<
  Path extends JsonPostPath,
  Status extends number,
> = JsonPayload<ResponseContent<ResponseForStatus<OperationFor<Path, "post">, Status>>>;

type ApiRequestInit = Omit<RequestInit, "body" | "method">;
type ApiPathWithSearch<Path extends string> = Path | `${Path}?${string}`;

export type ApiResponse<Payload> = {
  status: number;
  data: Payload;
};

export class ApiClientError extends Error {
  constructor(
    readonly method: string,
    readonly path: string,
    readonly status: number,
    readonly responseText: string,
  ) {
    super(`${method} ${path} failed with status ${status}`);
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

export async function getJsonByPath<Payload>(path: string, init: ApiRequestInit = {}): Promise<Payload> {
  const response = await get(path, "application/json", [200], init);

  return (await response.json()) as Payload;
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

export async function postJsonResponse<
  Path extends JsonPostPath & string,
  Status extends number,
>(
  path: Path,
  body: JsonPostBody<Path>,
  allowedStatuses: readonly Status[],
  init: ApiRequestInit = {},
): Promise<ApiResponse<JsonPostResponse<Path, Status>>> {
  const response = await post(path, body, "application/json", allowedStatuses, init);

  return {
    status: response.status,
    data: (await response.json()) as JsonPostResponse<Path, Status>,
  };
}

async function get(
  path: string,
  accept: string,
  allowedStatuses: readonly number[],
  init: ApiRequestInit,
): Promise<Response> {
  return request("GET", path, accept, allowedStatuses, init);
}

async function post(
  path: string,
  body: unknown,
  accept: string,
  allowedStatuses: readonly number[],
  init: ApiRequestInit,
): Promise<Response> {
  return request("POST", path, accept, allowedStatuses, init, body);
}

async function request(
  method: "GET" | "POST",
  path: string,
  accept: string,
  allowedStatuses: readonly number[],
  init: ApiRequestInit,
  body?: unknown,
): Promise<Response> {
  const headers = new Headers(init.headers);

  if (!headers.has("Accept")) {
    headers.set("Accept", accept);
  }

  if (body !== undefined && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(path, {
    ...init,
    body: body === undefined ? undefined : JSON.stringify(body),
    method,
    headers,
  });

  if (!allowedStatuses.includes(response.status)) {
    throw new ApiClientError(method, path, response.status, await response.text());
  }

  return response;
}

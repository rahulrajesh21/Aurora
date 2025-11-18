export interface ErrorResponse {
  error: string;
  message: string;
}

export function createError(error: string, message: string): ErrorResponse {
  return { error, message };
}

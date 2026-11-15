import Alert from '@mui/material/Alert';
import Button from '@mui/material/Button';
import { messageFor, type ErrorContext } from '../api/errorMessages';
import { isApiError } from '../api/problem';

/**
 * `problem+json` hatasını Türkçe mesaja çevirip gösterir (mapping §0.3, §0.4).
 * Mesajı olmayan kodlarda (ör. AUTH_TOKEN_EXPIRED) hiçbir şey çizmez.
 */
export function ErrorAlert({
  error,
  context = 'default',
  onRetry,
}: {
  error: unknown;
  context?: ErrorContext;
  onRetry?: () => void;
}) {
  if (!error) return null;
  const message = messageFor(error, context);
  if (!message) return null;
  const severity = isApiError(error) && error.code === 'SERVICE_UNAVAILABLE' ? 'warning' : 'error';
  return (
    <Alert
      severity={severity}
      role="alert"
      action={
        onRetry ? (
          <Button color="inherit" size="small" onClick={onRetry}>
            Tekrar dene
          </Button>
        ) : undefined
      }
    >
      {message}
    </Alert>
  );
}

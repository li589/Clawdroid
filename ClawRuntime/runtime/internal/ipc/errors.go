package ipc

const (
	CodeOK                          = 0
	CodeErrUnknown                  = 1
	CodeErrInvalidRequest           = 2
	CodeErrUnsupportedVer           = 3
	CodeErrTimeout                  = 4
	CodeErrCancelled                = 5
	CodeErrPayloadTooLarge          = 6
	CodeErrActionNotAllowed         = 7
	CodeErrRateLimited              = 8
	CodeErrPeerVerifyFailed         = 1001
	CodeErrSignatureMismatch        = 1002
	CodeErrChallengeFailed          = 1003
	CodeErrSessionExpired           = 1004
	CodeErrCapabilityTokenInvalid   = 1005
	CodeErrRootUnavailable          = 2001
	CodeErrAccessibilityUnavailable = 2002
	CodeErrScreenCaptureUnavailable = 2003
	CodeErrCapabilityNotGranted     = 2004
	CodeErrInputInjectFailed        = 3001
	CodeErrShellDenied              = 3002
	CodeErrShellExecFailed          = 3003
	CodeErrFileOutOfScope           = 3004
	CodeErrFileReadFailed           = 3005
	CodeErrFileWriteFailed          = 3006
	CodeErrAdapterNotAvailable      = 4001
	CodeErrTargetVersionUnsupported = 4002
	CodeErrTargetUIChanged          = 4003
	CodeErrSELinuxDenied            = 5001
	CodeErrDaemonUnhealthy          = 5002
	CodeErrROMUnsupported           = 5003
	CodeErrTaskNotFound             = 7001
	CodeErrTaskStateInvalid         = 7002
	CodeErrTaskSubmitFailed         = 7003
	CodeErrTaskCancelFailed         = 7004
	CodeErrTaskQueueFull            = 7005
)

func ErrorMessage(code int) string {
	switch code {
	case CodeOK:
		return "success"
	case CodeErrInvalidRequest:
		return "invalid request"
	case CodeErrUnsupportedVer:
		return "unsupported protocol version"
	case CodeErrTimeout:
		return "request timeout"
	case CodeErrCancelled:
		return "request cancelled"
	case CodeErrPayloadTooLarge:
		return "payload too large"
	case CodeErrActionNotAllowed:
		return "action not allowed"
	case CodeErrRateLimited:
		return "rate limited"
	case CodeErrPeerVerifyFailed:
		return "peer verification failed"
	case CodeErrSignatureMismatch:
		return "signature mismatch"
	case CodeErrChallengeFailed:
		return "challenge verification failed"
	case CodeErrSessionExpired:
		return "session expired"
	case CodeErrCapabilityTokenInvalid:
		return "capability token invalid"
	case CodeErrRootUnavailable:
		return "root unavailable"
	case CodeErrAccessibilityUnavailable:
		return "accessibility unavailable"
	case CodeErrScreenCaptureUnavailable:
		return "screen capture unavailable"
	case CodeErrCapabilityNotGranted:
		return "capability not granted"
	case CodeErrInputInjectFailed:
		return "input inject failed"
	case CodeErrShellDenied:
		return "shell denied"
	case CodeErrShellExecFailed:
		return "shell exec failed"
	case CodeErrFileOutOfScope:
		return "file out of scope"
	case CodeErrFileReadFailed:
		return "file read failed"
	case CodeErrFileWriteFailed:
		return "file write failed"
	case CodeErrAdapterNotAvailable:
		return "adapter not available"
	case CodeErrTargetVersionUnsupported:
		return "target version unsupported"
	case CodeErrTargetUIChanged:
		return "target ui changed"
	case CodeErrSELinuxDenied:
		return "selinux denied"
	case CodeErrDaemonUnhealthy:
		return "daemon unhealthy"
	case CodeErrROMUnsupported:
		return "rom unsupported"
	case CodeErrTaskNotFound:
		return "task not found"
	case CodeErrTaskStateInvalid:
		return "invalid task state transition"
	case CodeErrTaskSubmitFailed:
		return "task submission failed"
	case CodeErrTaskCancelFailed:
		return "task cancellation failed"
	case CodeErrTaskQueueFull:
		return "task queue is full"
	default:
		return "unknown error"
	}
}

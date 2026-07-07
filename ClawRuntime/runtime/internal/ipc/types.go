package ipc

type Request struct {
    Version    int                    `json:"version"`
    RequestID  string                 `json:"request_id"`
    Timestamp  int64                  `json:"timestamp"`
    Action     string                 `json:"action"`
    Capability string                 `json:"capability"`
    Args       map[string]interface{} `json:"args"`
}

type Response struct {
    RequestID string                 `json:"request_id"`
    OK        bool                   `json:"ok"`
    Code      int                    `json:"code"`
    Message   string                 `json:"message"`
    Data      map[string]interface{} `json:"data"`
}

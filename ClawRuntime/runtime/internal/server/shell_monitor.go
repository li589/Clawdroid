package server

import (
	"fmt"
	"sync"
	"time"
)

const (
	maxShellJobsRetained   = 16
	maxShellJobOutputBytes = 4096
)

// ShellJob is a bounded snapshot of a whitelist shell execution for monitoring.
type ShellJob struct {
	ID           string `json:"job_id"`
	SessionID    string `json:"session_id"`
	Command      string `json:"command"`
	TemplateName string `json:"template_name"`
	State        string `json:"state"` // running|succeeded|failed|timeout|cancelled
	ExitCode     int    `json:"exit_code"`
	StdoutTail   string `json:"stdout_tail"`
	StderrTail   string `json:"stderr_tail"`
	Truncated    bool   `json:"truncated"`
	TimedOut     bool   `json:"timed_out"`
	DurationMS   int64  `json:"duration_ms"`
	StartedAt    int64  `json:"started_at_epoch_ms"`
	EndedAt      int64  `json:"ended_at_epoch_ms,omitempty"`
}

type shellJobTracker struct {
	mu    sync.RWMutex
	seq   uint64
	jobs  map[string]*ShellJob
	order []string
}

func newShellJobTracker() *shellJobTracker {
	return &shellJobTracker{jobs: make(map[string]*ShellJob)}
}

func (t *shellJobTracker) begin(sessionID, command, templateName string) *ShellJob {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.seq++
	id := fmt.Sprintf("shell-%d-%d", time.Now().UnixMilli(), t.seq)
	job := &ShellJob{
		ID:           id,
		SessionID:    sessionID,
		Command:      command,
		TemplateName: templateName,
		State:        "running",
		StartedAt:    time.Now().UnixMilli(),
	}
	t.jobs[id] = job
	t.order = append(t.order, id)
	t.pruneLocked()
	return cloneShellJob(job)
}

func (t *shellJobTracker) finish(id string, state string, exitCode int, stdout, stderr string, truncated, timedOut bool, durationMS int64) *ShellJob {
	t.mu.Lock()
	defer t.mu.Unlock()
	job := t.jobs[id]
	if job == nil {
		return nil
	}
	job.State = state
	job.ExitCode = exitCode
	job.StdoutTail = truncateUTF8(stdout, maxShellJobOutputBytes)
	job.StderrTail = truncateUTF8(stderr, maxShellJobOutputBytes)
	job.Truncated = truncated || len(stdout) > maxShellJobOutputBytes || len(stderr) > maxShellJobOutputBytes
	job.TimedOut = timedOut
	job.DurationMS = durationMS
	job.EndedAt = time.Now().UnixMilli()
	return cloneShellJob(job)
}

func (t *shellJobTracker) get(id string) (*ShellJob, bool) {
	t.mu.RLock()
	defer t.mu.RUnlock()
	job, ok := t.jobs[id]
	if !ok {
		return nil, false
	}
	return cloneShellJob(job), true
}

func (t *shellJobTracker) list(limit int) []ShellJob {
	t.mu.RLock()
	defer t.mu.RUnlock()
	if limit <= 0 || limit > len(t.order) {
		limit = len(t.order)
	}
	out := make([]ShellJob, 0, limit)
	start := len(t.order) - limit
	if start < 0 {
		start = 0
	}
	for _, id := range t.order[start:] {
		if job := t.jobs[id]; job != nil {
			out = append(out, *cloneShellJob(job))
		}
	}
	return out
}

func (t *shellJobTracker) snapshotMaps(limit int) []map[string]interface{} {
	jobs := t.list(limit)
	out := make([]map[string]interface{}, 0, len(jobs))
	for _, job := range jobs {
		out = append(out, map[string]interface{}{
			"job_id":            job.ID,
			"session_id":        job.SessionID,
			"command":           job.Command,
			"template_name":     job.TemplateName,
			"state":             job.State,
			"exit_code":         job.ExitCode,
			"stdout_tail":       job.StdoutTail,
			"stderr_tail":       job.StderrTail,
			"truncated":         job.Truncated,
			"timed_out":         job.TimedOut,
			"duration_ms":       job.DurationMS,
			"started_at_epoch_ms": job.StartedAt,
			"ended_at_epoch_ms": job.EndedAt,
		})
	}
	return out
}

func (t *shellJobTracker) pruneLocked() {
	for len(t.order) > maxShellJobsRetained {
		old := t.order[0]
		t.order = t.order[1:]
		delete(t.jobs, old)
	}
}

func cloneShellJob(job *ShellJob) *ShellJob {
	if job == nil {
		return nil
	}
	cp := *job
	return &cp
}

func truncateUTF8(s string, maxBytes int) string {
	if maxBytes <= 0 || len(s) <= maxBytes {
		return s
	}
	b := []byte(s)
	cut := maxBytes
	for cut > 0 && (b[cut]&0xC0) == 0x80 {
		cut--
	}
	if cut <= 0 {
		cut = maxBytes
	}
	return string(b[:cut]) + "…(truncated)"
}

package runtimeapp

import (
	"context"
	"flag"
	"log"
	"os"
	"os/signal"
	"syscall"

	"clawdroid/runtime/internal/audit"
	"clawdroid/runtime/internal/config"
	"clawdroid/runtime/internal/server"
)

func Run() {
	configPath := flag.String("config", "", "path to the runtime config file")
	flag.Parse()

	cfg := config.Default()

	var logger *audit.Logger
	var err error

	if *configPath != "" {
		loadedCfg, loadErr := config.Load(*configPath)
		if loadErr != nil {
			log.Fatalf("failed to load runtime config: %v", loadErr)
		}
		cfg = loadedCfg
	}

	if err := cfg.Validate(); err != nil {
		log.Fatalf("invalid runtime config: %v", err)
	}

	auditDir := cfg.AuditDir
	if auditDir == "" {
		auditDir = "/data/local/tmp/clawdroid/audit"
	}

	logger, err = audit.NewLoggerWithFileLogger(auditDir)
	if err != nil {
		log.Fatalf("failed to create audit logger: %v", err)
	}
	defer logger.Close()

	audit.SetVersionInfo(server.DaemonVersion, server.DaemonBuildTime)
	logger.Info("runtime config loaded from: " + *configPath)
	logger.Info("audit log directory: " + auditDir)

	srv := server.New(cfg, logger)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if err := srv.Start(ctx); err != nil {
		log.Fatalf("runtime terminated with error: %v", err)
	}
}

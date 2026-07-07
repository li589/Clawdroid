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
	logger := audit.NewLogger()

	if *configPath != "" {
		loadedCfg, err := config.Load(*configPath)
		if err != nil {
			log.Fatalf("failed to load runtime config: %v", err)
		} else {
			cfg = loadedCfg
			logger.Info("runtime config loaded from: " + *configPath)
		}
	}

	if err := cfg.Validate(); err != nil {
		log.Fatalf("invalid runtime config: %v", err)
	}

	srv := server.New(cfg, logger)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if err := srv.Start(ctx); err != nil {
		log.Fatalf("runtime terminated with error: %v", err)
	}
}

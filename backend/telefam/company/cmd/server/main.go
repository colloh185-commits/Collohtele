package main

import (
	"context"
	"crypto/rand"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"telefam.company/backend/internal/audit"
	"telefam.company/backend/internal/auth"
	"telefam.company/backend/internal/config"
	"telefam.company/backend/internal/contacts"
	"telefam.company/backend/internal/fingerprint"
	"telefam.company/backend/internal/mailer"
	"telefam.company/backend/internal/ratelimit"
)

func main() {
	for _, candidate := range []string{".env", "../../.env", "backend/telefam/company/.env"} {
		config.LoadDotEnv(candidate)
	}
	secret := loadHMACSecret()
	fpService := fingerprint.NewService(secret)
	rateLimiter := ratelimit.NewLimiter()
	otpService := auth.NewOtpService(secret, os.Getenv("TELEFAM_PUBLIC_BASE_URL"))
	tokenService := auth.NewTokenService(secret)
	auditLogger := audit.NewLogger()
	resendMailer := mailer.NewResendClientFromEnv()
	contactsService := contacts.NewService()
	log.Printf("Telefam Resend mailer configured=%t", resendMailer.IsConfigured())
	handler := auth.NewHandler(fpService, rateLimiter, otpService, tokenService, auditLogger, resendMailer, contactsService)

	mux := http.NewServeMux()
	handler.RegisterRoutes(mux)

	port := os.Getenv("TELEFAM_PORT")
	if port == "" {
		port = "8080"
	}

	srv := &http.Server{
		Addr:              ":" + port,
		Handler:           securityHeadersMiddleware(mux),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       300 * time.Second,
		WriteTimeout:      300 * time.Second,
		IdleTimeout:       120 * time.Second,
	}

	go func() {
		log.Printf("Telefam Go backend listening on :%s", port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("server failed: %v", err)
		}
	}()

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	<-stop

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = srv.Shutdown(ctx)
}

func loadHMACSecret() []byte {
	if envSecret := os.Getenv("TELEFAM_HMAC_SECRET"); len(envSecret) >= 32 {
		return []byte(envSecret)
	}
	buf := make([]byte, 32)
	_, _ = rand.Read(buf)
	return buf
}

func securityHeadersMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Frame-Options", "DENY")
		w.Header().Set("Referrer-Policy", "no-referrer")
		next.ServeHTTP(w, r)
	})
}

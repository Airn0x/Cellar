package main

import (
	"context"
	"net"
	"os"
	"time"
)

// setupCerts points Go's TLS stack at a CA bundle on systems without
// the standard Linux locations (Termux/Android). Must run before the
// first TLS connection; Go reads SSL_CERT_FILE/SSL_CERT_DIR once.
func setupCerts() {
	if os.Getenv("SSL_CERT_FILE") != "" || os.Getenv("SSL_CERT_DIR") != "" {
		return
	}
	for _, f := range []string{"/etc/ssl/certs/ca-certificates.crt", "/etc/ssl/cert.pem"} {
		if _, err := os.Stat(f); err == nil {
			return // standard location present; Go finds it itself
		}
	}
	if p := os.Getenv("PREFIX"); p != "" { // Termux ca-certificates bundle
		if f := p + "/etc/tls/cert.pem"; statOK(f) {
			os.Setenv("SSL_CERT_FILE", f)
			return
		}
	}
	if statOK("/data/data/com.termux/files/usr/etc/tls/cert.pem") {
		os.Setenv("SSL_CERT_FILE", "/data/data/com.termux/files/usr/etc/tls/cert.pem")
		return
	}
	for _, d := range []string{"/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts"} {
		if statOK(d) {
			os.Setenv("SSL_CERT_DIR", d)
			return
		}
	}
}

func statOK(path string) bool {
	_, err := os.Stat(path)
	return err == nil
}

// Android has no /etc/resolv.conf, and a static (CGO-free) Go binary
// can't use Bionic's resolver — the pure-Go resolver then tries
// localhost:53 and dies. When the file is absent, resolve via public
// DNS directly ($CELLAR_DNS overrides the server list).
func setupResolver() {
	if _, err := os.Stat("/etc/resolv.conf"); err == nil {
		return
	}
	servers := []string{"1.1.1.1:53", "8.8.8.8:53"}
	if s := os.Getenv("CELLAR_DNS"); s != "" {
		servers = []string{net.JoinHostPort(s, "53")}
	}
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			d := net.Dialer{Timeout: 5 * time.Second}
			var lastErr error
			for _, s := range servers {
				conn, err := d.DialContext(ctx, network, s)
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}
			return nil, lastErr
		},
	}
}

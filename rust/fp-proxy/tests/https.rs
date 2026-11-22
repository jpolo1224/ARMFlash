//! https:// game files: CONNECT through the proxy, TLS ended locally, served from the zip.

use std::io::Write;
use std::sync::Arc;

use fp_proxy::{start, Config, GameServer};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, SignatureScheme};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

#[derive(Debug)]
struct AcceptAny;

impl ServerCertVerifier for AcceptAny {
    fn verify_server_cert(&self, end: &CertificateDer<'_>, _: &[CertificateDer<'_>], name: &ServerName<'_>, _: &[u8], _: UnixTime) -> Result<ServerCertVerified, rustls::Error> {
        // The certificate must at least be for the host that was asked for.
        assert!(!end.is_empty());
        assert_eq!(name.to_str(), "secure.example.com");
        Ok(ServerCertVerified::assertion())
    }
    fn verify_tls12_signature(&self, _: &[u8], _: &CertificateDer<'_>, _: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }
    fn verify_tls13_signature(&self, _: &[u8], _: &CertificateDer<'_>, _: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        Ok(HandshakeSignatureValid::assertion())
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        rustls::crypto::ring::default_provider().signature_verification_algorithms.supported_schemes()
    }
}

#[tokio::test]
async fn https_is_served_from_the_zip() {
    let dir = tempfile::tempdir().unwrap();
    let zip_path = dir.path().join("s.zip");
    let mut w = zip::ZipWriter::new(std::fs::File::create(&zip_path).unwrap());
    w.start_file("content/secure.example.com/game/data.json", zip::write::SimpleFileOptions::default()).unwrap();
    w.write_all(br#"{"level":1}"#).unwrap();
    w.finish().unwrap();

    let srv = GameServer::new(Config::default());
    srv.mount(&zip_path).unwrap();
    let h = start(srv, 0).unwrap();

    let mut tcp = tokio::net::TcpStream::connect(("127.0.0.1", h.port)).await.unwrap();
    tcp.write_all(b"CONNECT secure.example.com:443 HTTP/1.1\r\nHost: secure.example.com:443\r\n\r\n").await.unwrap();
    let mut buf = [0u8; 256];
    let n = tcp.read(&mut buf).await.unwrap();
    assert!(String::from_utf8_lossy(&buf[..n]).starts_with("HTTP/1.1 200"), "{}", String::from_utf8_lossy(&buf[..n]));

    let cfg = rustls::ClientConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()
        .unwrap()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(AcceptAny))
        .with_no_client_auth();
    let connector = tokio_rustls::TlsConnector::from(Arc::new(cfg));
    let mut tls = connector.connect(ServerName::try_from("secure.example.com").unwrap(), tcp).await.unwrap();
    tls.write_all(b"GET /Game/Data.json HTTP/1.1\r\nHost: secure.example.com\r\nConnection: close\r\n\r\n").await.unwrap();
    let mut out = Vec::new();
    tls.read_to_end(&mut out).await.ok();
    let text = String::from_utf8_lossy(&out);
    assert!(text.starts_with("HTTP/1.1 200"), "{text}");
    assert!(text.ends_with(r#"{"level":1}"#), "{text}");
    h.stop();
}

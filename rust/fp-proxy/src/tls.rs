//! HTTPS for archived games: the game server ends TLS itself.
//!
//! A CONNECT through the proxy is answered locally: the connection is upgraded, TLS is
//! accepted with a certificate made on the spot for that host (cached per host), and the
//! requests inside are served exactly like plain http:// ones, from the data packs. Nothing
//! leaves the device unless the remote Legacy fallback is on. The game's WebView accepts
//! these certificates because everything it loads goes through this server.

use std::collections::HashMap;
use std::io;
use std::sync::{Arc, Mutex, OnceLock};

use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use rustls::ServerConfig;

fn cache() -> &'static Mutex<HashMap<String, Arc<ServerConfig>>> {
    static C: OnceLock<Mutex<HashMap<String, Arc<ServerConfig>>>> = OnceLock::new();
    C.get_or_init(|| Mutex::new(HashMap::new()))
}

/// TLS server config presenting a certificate for `host`.
pub fn config_for(host: &str) -> io::Result<Arc<ServerConfig>> {
    let host = host.to_ascii_lowercase();
    if let Some(c) = cache().lock().unwrap().get(&host) {
        return Ok(c.clone());
    }
    let ck = rcgen::generate_simple_self_signed(vec![host.clone()]).map_err(io::Error::other)?;
    let cert: CertificateDer<'static> = ck.cert.der().clone();
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(ck.signing_key.serialize_der()));
    let mut cfg = ServerConfig::builder_with_provider(Arc::new(rustls::crypto::ring::default_provider()))
        .with_safe_default_protocol_versions()
        .map_err(io::Error::other)?
        .with_no_client_auth()
        .with_single_cert(vec![cert], key)
        .map_err(io::Error::other)?;
    cfg.alpn_protocols = vec![b"http/1.1".to_vec()];
    let cfg = Arc::new(cfg);
    cache().lock().unwrap().insert(host, cfg.clone());
    Ok(cfg)
}

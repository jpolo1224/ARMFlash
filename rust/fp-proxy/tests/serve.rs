//! End-to-end: real zips in Flashpoint's data-pack layout, real HTTP over loopback.

use std::io::{Read, Write};
use std::net::TcpStream;
use std::path::Path;

use fp_proxy::{start, Config, GameServer};
use zip::write::SimpleFileOptions;
use zip::CompressionMethod;

fn make_zip(path: &Path, entries: &[(&str, &[u8], CompressionMethod)]) {
    let mut w = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
    for (name, data, method) in entries {
        w.start_file(*name, SimpleFileOptions::default().compression_method(*method)).unwrap();
        w.write_all(data).unwrap();
    }
    w.finish().unwrap();
}

struct Resp {
    status: u16,
    headers: String,
    body: Vec<u8>,
}

fn get(port: u16, request_target: &str, host: &str) -> Resp {
    let mut s = TcpStream::connect(("127.0.0.1", port)).unwrap();
    write!(s, "GET {request_target} HTTP/1.1\r\nHost: {host}\r\nConnection: close\r\n\r\n").unwrap();
    let mut raw = Vec::new();
    s.read_to_end(&mut raw).unwrap();
    let split = raw.windows(4).position(|w| w == b"\r\n\r\n").expect("header end");
    let headers = String::from_utf8_lossy(&raw[..split]).to_string();
    let status = headers[9..12].parse().unwrap();
    Resp { status, headers: headers.to_lowercase(), body: raw[split + 4..].to_vec() }
}

fn big_payload() -> Vec<u8> {
    // 3 MiB, semi-compressible, so the streamed path and inflate both get exercised.
    (0..3 * 1024 * 1024u32).map(|i| (i.wrapping_mul(2654435761) >> 13) as u8 & 0x3f).collect()
}

#[test]
fn serves_like_the_flashpoint_game_server() {
    let dir = tempfile::tempdir().unwrap();
    let big = big_payload();
    let a = dir.path().join("a.zip");
    make_zip(&a, &[
        ("content/www.Example.com/Game/Main.SWF", b"FWS-main", CompressionMethod::Deflated),
        ("content/www.example.com/big/data.bin", &big, CompressionMethod::Deflated),
        ("content/www.example.com/stored/raw.bin", &big[..1024 * 1024], CompressionMethod::Stored),
        ("content/www.example.com/dir/index.html", b"<html>dir</html>", CompressionMethod::Deflated),
        ("content/www.example.com/shared.txt", b"from-a", CompressionMethod::Deflated),
    ]);
    let b = dir.path().join("b.zip");
    make_zip(&b, &[("content/www.example.com/shared.txt", b"from-b", CompressionMethod::Deflated)]);

    let htdocs = dir.path().join("htdocs");
    std::fs::create_dir_all(htdocs.join("legacy.com/q")).unwrap();
    std::fs::write(htdocs.join("legacy.com/q/page.php?id=7"), b"query-variant").unwrap();
    std::fs::write(htdocs.join("legacy.com/q/page.php"), b"plain").unwrap();

    let srv = GameServer::new(Config { htdocs: Some(htdocs), ..Default::default() });
    assert_eq!(srv.mount(&a).unwrap(), 5);
    srv.mount(&b).unwrap();
    srv.mount(&a).unwrap(); // second mount is a no-op
    assert_eq!(srv.mounted().len(), 2);
    let h = start(srv.clone(), 0).unwrap();
    let port = h.port;

    // Proxy-style absolute URL, different case than the zip.
    let r = get(port, "http://WWW.EXAMPLE.COM/game/main.swf?cachebust=1", "www.example.com");
    assert_eq!(r.status, 200);
    assert_eq!(r.body, b"FWS-main");
    assert!(r.headers.contains("content-type: application/x-shockwave-flash"), "{}", r.headers);

    // Large deflated entry is streamed and arrives intact.
    let r = get(port, "http://www.example.com/big/data.bin", "www.example.com");
    assert_eq!(r.status, 200);
    assert!(r.headers.contains(&format!("content-length: {}", big.len())));
    assert_eq!(r.body, big);

    let r = get(port, "http://www.example.com/stored/raw.bin", "www.example.com");
    assert_eq!(r.body, &big[..1024 * 1024]);

    // Directory: redirect to add the slash, then serve the index.
    let r = get(port, "http://www.example.com/dir?x=1", "www.example.com");
    assert_eq!(r.status, 301);
    assert!(r.headers.contains("location: /dir/?x=1"), "{}", r.headers);
    let r = get(port, "http://www.example.com/dir/", "www.example.com");
    assert_eq!(r.body, b"<html>dir</html>");
    assert!(r.headers.contains("content-type: text/html"));

    // Mount order: the first mounted pack wins.
    assert_eq!(get(port, "http://www.example.com/shared.txt", "www.example.com").body, b"from-a");

    // Legacy htdocs, including the `path?query` variant the game server tries first.
    assert_eq!(get(port, "http://legacy.com/q/page.php?id=7", "legacy.com").body, b"query-variant");
    assert_eq!(get(port, "http://legacy.com/q/page.php", "legacy.com").body, b"plain");

    // Direct mode (origin-form to the proxy itself).
    let me = format!("127.0.0.1:{port}");
    assert_eq!(get(port, "/www.example.com/Game/Main.swf", &me).body, b"FWS-main");

    // Misses, traversal attempts, and HTTPS tunnels.
    assert_eq!(get(port, "http://www.example.com/nope.swf", "www.example.com").status, 404);
    assert_eq!(get(port, "http://www.example.com/../../etc/passwd", "www.example.com").status, 404);

    // In-process read for native players.
    assert_eq!(srv.read("http://www.example.com/GAME/main.swf").unwrap(), b"FWS-main");
    assert!(srv.read("http://www.example.com/missing").is_none());

    srv.unmount(&a);
    assert_eq!(get(port, "http://www.example.com/shared.txt", "www.example.com").body, b"from-b");
    h.stop();
}

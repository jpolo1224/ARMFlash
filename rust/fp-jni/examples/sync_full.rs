//! Full metadata sync into a fresh database, as the app does on first run.
//!   cargo run --release -p fp-jni --example sync_full -- <db path>
fn main() {
    let path = std::env::args().nth(1).expect("db path");
    let _ = std::fs::remove_file(&path);
    let t = std::time::Instant::now();
    fpcore::core::open_database(&path).unwrap();
    fpcore::sync::start("https://fpfss.flashpointarchive.org", "").unwrap();
    loop {
        std::thread::sleep(std::time::Duration::from_secs(5));
        let s: serde_json::Value = serde_json::from_str(&fpcore::sync::status().unwrap()).unwrap();
        println!("{:>5.0}s {}", t.elapsed().as_secs_f32(), s);
        if !s["running"].as_bool().unwrap_or(false) { break; }
    }
    println!("{}", fpcore::core::open_database(&path).unwrap());
    let r = fpcore::core::search_page("mario 63", "{}", 5).unwrap();
    println!("search 'mario 63': {}", &r[..r.len().min(300)]);
    println!("db size: {} MB", std::fs::metadata(&path).unwrap().len() >> 20);
}

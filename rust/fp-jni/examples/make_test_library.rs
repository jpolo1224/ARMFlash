//! Builds a small Flashpoint library for device testing, in the real on-disk layout:
//!   <out>/Data/flashpoint.sqlite   (made by flashpoint-archive itself, so the schema is real)
//!   <out>/Data/Games/<id>-<ms>.zip (data packs in content/<host>/<path> layout)
//!
//!   cargo run -p fp-jni --example make_test_library -- <out dir>

use std::io::Write;
use std::path::Path;

use flashpoint_archive::game::PartialGame;
use flashpoint_archive::game_data::PartialGameData;
use flashpoint_archive::FlashpointArchive;
use serde_json::json;

fn zip(path: &Path, entries: &[(&str, &[u8])]) -> (String, i64) {
    let mut w = zip::ZipWriter::new(std::fs::File::create(path).unwrap());
    for (name, data) in entries {
        w.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
        w.write_all(data).unwrap();
    }
    w.finish().unwrap();
    let bytes = std::fs::read(path).unwrap();
    (sha256_hex(&bytes), bytes.len() as i64)
}

/// A minimal SWF 10: 550x400 stage, 30 fps, a green background, a red rectangle, one frame.
fn tiny_swf() -> Vec<u8> {
    let mut body = Vec::new();
    // RECT 0..11000 x 0..8000 twips, 15 bits per field.
    let mut bits = BitWriter::default();
    bits.put(15, 5);
    for v in [0u32, 11000, 0, 8000] {
        bits.put(v, 15);
    }
    body.extend(bits.finish());
    body.extend([0x00, 30]); // frame rate 30.0 (8.8 fixed)
    body.extend(1u16.to_le_bytes()); // frame count
    // SetBackgroundColor (9)
    tag(&mut body, 9, &[0x2E, 0x9E, 0x3A]);
    // DefineShape (2), id 1: a red filled rectangle 100..450 x 100..300 px.
    let mut sh = Vec::new();
    sh.extend(1u16.to_le_bytes());
    let mut b = BitWriter::default();
    b.put(15, 5);
    for v in [2000u32, 9000, 2000, 6000] {
        b.put(v, 15);
    }
    sh.extend(b.finish());
    sh.extend([1, 0x00, 0xE0, 0x30, 0x30]); // 1 fill style: solid red-ish
    sh.push(0); // 0 line styles
    sh.push(0x10); // NumFillBits=1, NumLineBits=0
    let mut s = BitWriter::default();
    // StyleChangeRecord: moveTo + fillStyle1
    s.put(0, 1); // non-edge
    s.put(0b00101, 5); // flags: stateFillStyle1 | stateMoveTo
    s.put(15, 5);
    s.put(2000, 15);
    s.put(2000, 15);
    s.put(1, 1); // fill style 1
    for (dx, dy) in [(7000i32, 0i32), (0, 4000), (-7000, 0), (0, -4000)] {
        s.put(1, 1); // edge
        s.put(1, 1); // straight
        s.put(14 - 2, 4); // NumBits - 2 => 14 bits
        if dy == 0 {
            s.put(0, 1); // not general line
            s.put(0, 1); // horizontal
            s.put((dx as u32) & 0x3FFF, 14);
        } else {
            s.put(0, 1);
            s.put(1, 1); // vertical
            s.put((dy as u32) & 0x3FFF, 14);
        }
    }
    s.put(0, 6); // end of shape
    sh.extend(s.finish());
    tag(&mut body, 2, &sh);
    // PlaceObject2 (26): place character 1 at depth 1.
    tag(&mut body, 26, &[0x02, 0x01, 0x00, 0x01, 0x00]);
    tag(&mut body, 1, &[]); // ShowFrame
    tag(&mut body, 0, &[]); // End
    let mut swf = b"FWS".to_vec();
    swf.push(10);
    swf.extend(((body.len() + 8) as u32).to_le_bytes());
    swf.extend(body);
    swf
}

fn tag(out: &mut Vec<u8>, code: u16, data: &[u8]) {
    if data.len() < 0x3F {
        out.extend(((code << 6) | data.len() as u16).to_le_bytes());
    } else {
        out.extend(((code << 6) | 0x3F).to_le_bytes());
        out.extend((data.len() as u32).to_le_bytes());
    }
    out.extend(data);
}

#[derive(Default)]
struct BitWriter {
    out: Vec<u8>,
    cur: u8,
    n: u8,
}

impl BitWriter {
    fn put(&mut self, v: u32, bits: u8) {
        for i in (0..bits).rev() {
            self.cur = (self.cur << 1) | ((v >> i) & 1) as u8;
            self.n += 1;
            if self.n == 8 {
                self.out.push(self.cur);
                self.cur = 0;
                self.n = 0;
            }
        }
    }
    fn finish(mut self) -> Vec<u8> {
        if self.n > 0 {
            self.out.push(self.cur << (8 - self.n));
        }
        self.out
    }
}

fn sha256_hex(data: &[u8]) -> String {
    // Small self-contained SHA-256 so the example needs no extra crates.
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01,
        0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
        0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08,
        0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ];
    let mut h: [u32; 8] = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    let mut msg = data.to_vec();
    let bitlen = (data.len() as u64) * 8;
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend(bitlen.to_be_bytes());
    for chunk in msg.chunks(64) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes(chunk[i * 4..i * 4 + 4].try_into().unwrap());
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16].wrapping_add(s0).wrapping_add(w[i - 7]).wrapping_add(s1);
        }
        let mut v = h;
        for i in 0..64 {
            let s1 = v[4].rotate_right(6) ^ v[4].rotate_right(11) ^ v[4].rotate_right(25);
            let ch = (v[4] & v[5]) ^ (!v[4] & v[6]);
            let t1 = v[7].wrapping_add(s1).wrapping_add(ch).wrapping_add(K[i]).wrapping_add(w[i]);
            let s0 = v[0].rotate_right(2) ^ v[0].rotate_right(13) ^ v[0].rotate_right(22);
            let maj = (v[0] & v[1]) ^ (v[0] & v[2]) ^ (v[1] & v[2]);
            let t2 = s0.wrapping_add(maj);
            v = [t1.wrapping_add(t2), v[0], v[1], v[2], v[3].wrapping_add(t1), v[4], v[5], v[6]];
        }
        for i in 0..8 {
            h[i] = h[i].wrapping_add(v[i]);
        }
    }
    h.iter().map(|x| format!("{x:08x}")).collect()
}

const INPUT_TEST: &str = r#"<!doctype html><html><head><meta name="viewport" content="width=device-width">
<style>body{margin:0;background:#222;color:#fff;font:22px sans-serif;height:100vh}#k,#m{padding:16px}</style></head>
<body><div id="k">keys: </div><div id="m">mouse: </div><script>
const k=document.getElementById('k'),m=document.getElementById('m');let ks=[],ms=[];
addEventListener('keydown',e=>{ks.push('down '+e.key);ks=ks.slice(-6);k.textContent='keys: '+ks.join(' | ')});
addEventListener('keyup',e=>{ks.push('up '+e.key);ks=ks.slice(-6);k.textContent='keys: '+ks.join(' | ')});
let lastMove='';
addEventListener('mousemove',e=>{lastMove='move '+e.clientX+','+e.clientY+' ('+e.pointerType+')';m.textContent='mouse: '+ms.join(' | ')+' | '+lastMove});
['mousedown','mouseup','click','contextmenu'].forEach(t=>addEventListener(t,e=>{ms.push(t+' b'+e.button);ms=ms.slice(-5);m.textContent='mouse: '+ms.join(' | ');if(t=='contextmenu')e.preventDefault()}));
</script></body></html>"#;

const HTML5: &str = r#"<!doctype html><html><head><meta name="viewport" content="width=device-width">
<title>Bouncer</title><style>body{margin:0;background:#123}canvas{display:block;margin:auto}</style></head>
<body><canvas id="c" width="800" height="450"></canvas><script src="game.js"></script></body></html>"#;

const HTML5_JS: &str = r#"const c=document.getElementById('c'),x=c.getContext('2d');let b={x:100,y:100,vx:4,vy:3},t=0,f=0,last=performance.now(),fps=0;
function loop(n){f++;if(n-last>1000){fps=f;f=0;last=n}x.fillStyle='#123';x.fillRect(0,0,800,450);b.x+=b.vx;b.y+=b.vy;
if(b.x<20||b.x>780)b.vx*=-1;if(b.y<20||b.y>430)b.vy*=-1;x.fillStyle='#5cb531';x.beginPath();x.arc(b.x,b.y,20,0,7);x.fill();
x.fillStyle='#fff';x.font='28px sans-serif';x.fillText('Served from a data-pack zip | '+fps+' fps',20,40);requestAnimationFrame(loop)}requestAnimationFrame(loop);"#;

fn main() {
    let out = std::env::args().nth(1).expect("usage: make_test_library <out dir>");
    let out = Path::new(&out);
    let games_dir = out.join("Data/Games");
    std::fs::create_dir_all(&games_dir).unwrap();
    let db_path = out.join("Data/flashpoint.sqlite");
    let _ = std::fs::remove_file(&db_path);

    let mut fp = FlashpointArchive::new();
    fp.load_database(db_path.to_str().unwrap()).unwrap();
    let rt = tokio::runtime::Runtime::new().unwrap();

    let date = "2024-07-14T12:00:00.000Z";
    let ms = 1720958400000i64;
    let entries: Vec<(&str, &str, &str, &str, &str, Vec<(String, Vec<u8>)>)> = vec![
        (
            "11111111-aaaa-4bbb-8ccc-000000000001", "Bouncer (HTML5 test)", "HTML5", ":browser-mode:",
            "http://fp-test.example/bouncer/index.html",
            vec![
                ("content/fp-test.example/bouncer/index.html".into(), HTML5.as_bytes().to_vec()),
                ("content/fp-test.example/bouncer/game.js".into(), HTML5_JS.as_bytes().to_vec()),
            ],
        ),
        (
            "22222222-aaaa-4bbb-8ccc-000000000002", "Red Box (Flash test)", "Flash", r"FPSoftware\Flash\flashplayer_32_sa.exe",
            "http://www.fp-test.example/flash/RedBox.swf",
            vec![("content/www.fp-test.example/flash/redbox.swf".into(), tiny_swf())],
        ),
        (
            "55555555-aaaa-4bbb-8ccc-000000000005", "Input Tester", "HTML5", ":browser-mode:",
            "https://secure.fp-test.example/input/index.html",
            vec![("content/secure.fp-test.example/input/index.html".into(), INPUT_TEST.as_bytes().to_vec())],
        ),
        (
            "33333333-aaaa-4bbb-8ccc-000000000003", "Not Downloaded Yet", "Flash", r"FPSoftware\Flash\flashplayer_32_sa.exe",
            "http://nowhere.example/a.swf", vec![],
        ),
        (
            "44444444-aaaa-4bbb-8ccc-000000000004", "A Shockwave Game", "Shockwave", r"FPSoftware\Shockwave\PJX\SPR.exe",
            "http://sw.example/game.dcr", vec![],
        ),
    ];

    for (id, title, platform, app, cmd, files) in entries {
        let mut g: PartialGame = serde_json::from_value(json!({ "id": id })).unwrap();
        g.title = Some(title.into());
        g.primary_platform = Some(platform.into());
        g.platforms = Some(serde_json::from_value(json!(platform)).unwrap());
        g.library = Some("arcade".into());
        g.developer = Some("Flashpoint Android".into());
        g.tags = Some(serde_json::from_value(json!("Test;Arcade")).unwrap());
        g.play_mode = Some("Single Player".into());
        g.status = Some("Playable".into());
        g.date_added = Some(date.into());
        rt.block_on(fp.create_game(&g)).unwrap();

        let name = format!("{id}-{ms}.zip");
        let (sha, size, on_disk) = if files.is_empty() {
            ("0".repeat(64), 1234, false)
        } else {
            let refs: Vec<(&str, &[u8])> = files.iter().map(|(n, d)| (n.as_str(), d.as_slice())).collect();
            let (s, z) = zip(&games_dir.join(&name), &refs);
            (s, z, true)
        };
        let gd: PartialGameData = serde_json::from_value(json!({
            "game_id": id, "title": "Data", "date_added": date, "sha256": sha, "crc32": 0,
            "present_on_disk": on_disk, "path": if on_disk { Some(name.clone()) } else { None }, "size": size,
            "application_path": app, "launch_command": cmd,
        }))
        .unwrap();
        let saved = rt.block_on(fp.create_game_data(&gd)).unwrap();
        let mut g2: PartialGame = serde_json::from_value(json!({ "id": id })).unwrap();
        g2.active_data_id = Some(saved.id);
        g2.active_data_on_disk = Some(on_disk);
        rt.block_on(fp.save_game(&mut g2)).unwrap();
        println!("{title}: {name} on_disk={on_disk}");
    }
    println!("games: {}", rt.block_on(fp.count_games()).unwrap());
}

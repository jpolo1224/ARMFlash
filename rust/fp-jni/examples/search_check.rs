//! cargo run --example search_check -- <db> <query> [opts json]: prints the total and first titles.
fn main() {
    let a: Vec<String> = std::env::args().collect();
    fpcore::core::open_database(&a[1]).unwrap();
    let opts = a.get(3).cloned().unwrap_or_else(|| r#"{"library":"arcade"}"#.into());
    println!("{}", fpcore::core::search_index(&a[2], &opts, 120).unwrap().chars().take(200).collect::<String>());
    println!("{}", fpcore::core::search_page(&a[2], &opts, 5).unwrap().chars().take(400).collect::<String>());
}

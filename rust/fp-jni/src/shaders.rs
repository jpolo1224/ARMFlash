//! A RetroArch preset's tweakable parameters, for the shader settings (ARMSX2's
//! shaderPresetParams): every `#pragma parameter` across the chain, in the order the author
//! declared them, with the preset's own values as the initials.

use librashader_common::shader_features::ShaderFeatures;
use librashader_preprocess::ShaderSource;
use librashader_presets::ShaderPreset;

pub fn preset_params(path: &str) -> Result<String, String> {
    let preset = ShaderPreset::try_parse(path, ShaderFeatures::NONE).map_err(|e| e.to_string())?;
    let mut seen = std::collections::HashSet::new();
    let mut out = Vec::new();
    for pass in &preset.passes {
        let src = ShaderSource::load(&pass.path, ShaderFeatures::NONE).map_err(|e| e.to_string())?;
        // Preprocessing strips the #pragma lines and the table is unordered: order by where each
        // name is declared in the pass file and the files it includes (unknown ones last).
        let raw = raw_with_includes(&pass.path, 0);
        let mut names: Vec<&str> = src.parameters.keys().map(|k| k.as_str()).collect();
        names.sort_by_key(|n| raw.find(&format!("#pragma parameter {n} ")).unwrap_or(usize::MAX));
        for name in names {
            if !seen.insert(name.to_string()) {
                continue;
            }
            let p = &src.parameters[name];
            // A preset built on others (#reference) lists each file's value, the base's first:
            // the last one is what the chain runs with. (The first gave koko-aio's base values,
            // its bezel off among them, to every parameter the editor pushed.)
            let initial = preset.parameters.iter().rev().find(|m| m.name.as_str() == name).map(|m| m.value).unwrap_or(p.initial);
            out.push(serde_json::json!({
                "name": name, "description": p.description, "initial": initial,
                "minimum": p.minimum, "maximum": p.maximum, "step": p.step,
            }));
        }
    }
    Ok(serde_json::Value::Array(out).to_string())
}

/// A shader file's text with its #include files inlined (for ordering only).
fn raw_with_includes(path: &std::path::Path, depth: u32) -> String {
    let Ok(text) = std::fs::read_to_string(path) else { return String::new() };
    if depth > 8 {
        return text;
    }
    let mut out = String::new();
    for line in text.lines() {
        if let Some(rest) = line.trim().strip_prefix("#include") {
            let inc = rest.trim().trim_matches('"');
            if let Some(dir) = path.parent() {
                out.push_str(&raw_with_includes(&dir.join(inc), depth + 1));
            }
        } else {
            out.push_str(line);
        }
        out.push('\n');
    }
    out
}

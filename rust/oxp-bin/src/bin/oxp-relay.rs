// SPDX-FileCopyrightText: 2026 OpenXPlay.org
// SPDX-License-Identifier: AGPL-3.0-only
// Created by riko chen on 2026-10-14

use oxp_bin::{clink_support::*, *};
use oxp_clink::{
    ClinkAddresses, ClinkLink, ClinkListener, ClinkOutput, ClinkState, StaticClinkLink,
};
use oxp_core::{Hub, ProjectionOutput};
use oxp_web::{Pairing, Tls, WebOutput, WebOutputConfig};
use oxp_wire::{Message, Result};
use serde_json::json;
use std::{
    net::Ipv4Addr,
    path::Path,
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc::{sync_channel, RecvTimeoutError},
        Arc, Mutex,
    },
    time::{Duration, Instant},
};
const USAGE: &str = "oxp-relay (--unix PATH | --stdio) [--relay-name NAME --relay-version VERSION] [--web-port N --web-root DIR [--web-bind ADDR] [--plugins-root DIR --plugins-enabled IDS --plugins-state-dir DIR [--plugins-topics a,b,c]] [--advertise-address IPV4] [--pairing] [--token T] [--source-url URL] (--tls-cert PEM --tls-key PEM | --tls-self-signed STATE_DIR [--tls-san NAME]... | --no-tls)] [--clink-link static|ble --state-dir DIR [--pin DIGITS | --pin-file PATH] [--port k=v,...] [--clink-transcode auto|off]]\nStatic (default): --clink-head-unit IP --clink-local IP. BLE: [--clink-adapter NAME (hci0)] [--clink-interface NAME (wlan0)] [--clink-address MAC] [--clink-name NAME] [--clink-model MODEL] [--clink-id ID] [--clink-band 0|1]. BLE forbids static addresses. --pin-file clears stale input and waits up to 120 seconds per request. Events use stdout with --unix, stderr with --stdio.\n--clink-transcode auto (default) converts video the head unit cannot decode with the hardware transcoder, when one exists; off forwards it unchanged.\n--advertise-address overrides the IPv4 address in the pairing URL. Otherwise prefer the default-route interface, then Wi-Fi/Ethernet, then private address range.\nPairing is off by default. --pairing generates an 8-digit code; --token T enables pairing with T and takes precedence over --pairing.";
fn arguments() -> Result<(Args, Vec<String>)> {
    parse_arguments(std::env::args().skip(1))
}
fn parse_arguments(raw: impl IntoIterator<Item = String>) -> Result<(Args, Vec<String>)> {
    let mut raw = raw.into_iter();
    let mut values = Vec::new();
    let mut sans = Vec::new();
    while let Some(k) = raw.next() {
        if k == "--tls-san" {
            let v = raw
                .next()
                .filter(|v| !v.starts_with("--"))
                .ok_or("Missing --tls-san name")?;
            sans.push(v);
        } else {
            values.push(k);
        }
    }
    let args = Args::parse_from(
        values,
        &[
            "--unix",
            "--web-port",
            "--web-root",
            "--plugins-root",
            "--plugins-enabled",
            "--plugins-topics",
            "--plugins-state-dir",
            "--web-bind",
            "--advertise-address",
            "--token",
            "--pairing",
            "--source-url",
            "--relay-name",
            "--relay-version",
            "--tls-cert",
            "--tls-key",
            "--tls-self-signed",
            "--no-tls",
            "--clink-head-unit",
            "--clink-local",
            "--pin",
            "--pin-file",
            "--clink-link",
            "--clink-adapter",
            "--clink-interface",
            "--clink-address",
            "--clink-name",
            "--clink-model",
            "--clink-id",
            "--clink-band",
            "--state-dir",
            "--port",
            "--clink-transcode",
        ],
    )?;
    if args.get("--relay-name").is_some() != args.get("--relay-version").is_some()
        || ["--relay-name", "--relay-version"].iter().any(|k| {
            args.get(k).is_some_and(|v| {
                v.trim().is_empty() || v.len() > 128 || v.chars().any(char::is_control)
            })
        })
    {
        return Err(
            "Supply both --relay-name and --relay-version (1–128 bytes, no control characters)"
                .into(),
        );
    }
    advertise_address(&args)?;
    Ok((args, sans))
}
fn clink_link(args: &Args) -> Result<Option<Arc<dyn ClinkLink>>> {
    let mode = args.get("--clink-link").unwrap_or("static");
    if !["static", "ble"].contains(&mode) {
        return Err("--clink-link must be static or ble".into());
    }
    let ble_options = [
        "--clink-adapter",
        "--clink-interface",
        "--clink-address",
        "--clink-name",
        "--clink-model",
        "--clink-id",
        "--clink-band",
    ];
    if mode == "static" && ble_options.iter().any(|k| args.get(k).is_some()) {
        return Err("BLE options require --clink-link ble".into());
    }
    let enabled = mode == "ble"
        || args.get("--clink-link").is_some()
        || args.get("--clink-head-unit").is_some()
        || args.get("--clink-local").is_some();
    if !enabled {
        if [
            "--pin",
            "--pin-file",
            "--state-dir",
            "--port",
            "--clink-transcode",
        ]
        .iter()
        .any(|k| args.get(k).is_some())
        {
            return Err("Clink options require static addresses or --clink-link ble".into());
        }
        return Ok(None);
    }
    if args.get("--pin").is_some() && args.get("--pin-file").is_some() {
        return Err("Choose --pin or --pin-file".into());
    }
    if args
        .get("--pin")
        .is_some_and(|p| p.is_empty() || !p.bytes().all(|b| b.is_ascii_digit()))
    {
        return Err("PIN must contain digits".into());
    }
    if args.get("--pin-file").is_some_and(str::is_empty) {
        return Err("--pin-file requires a nonempty path".into());
    }
    if mode == "ble"
        && ["--clink-head-unit", "--clink-local"]
            .iter()
            .any(|k| args.get(k).is_some())
    {
        return Err("--clink-link ble forbids --clink-head-unit and --clink-local".into());
    }
    if mode == "static"
        && ["--clink-head-unit", "--clink-local"]
            .iter()
            .any(|k| args.get(k).is_none())
    {
        return Err("Static Clink requires --clink-head-unit and --clink-local".into());
    }
    args.need("--state-dir")?;
    parse_ports(args.get("--port"))?;
    transcode_enabled(args.get("--clink-transcode"))?;
    if mode == "static" {
        return Ok(Some(Arc::new(StaticClinkLink(ClinkAddresses::new(
            args.need("--clink-head-unit")?.parse()?,
            args.need("--clink-local")?.parse()?,
        )))));
    }
    let mut config = oxp_link_linux::Config::default();
    for (option, field) in [
        ("--clink-adapter", &mut config.bluetooth_adapter),
        ("--clink-interface", &mut config.wifi_interface),
        ("--clink-name", &mut config.device_name),
        ("--clink-model", &mut config.device_model),
        ("--clink-id", &mut config.device_id),
    ] {
        if let Some(value) = args.get(option) {
            *field = value.into();
        }
    }
    // The head unit creates its Wi-Fi group on this band: 0 is 2.4 GHz, 1 allows 5 GHz.
    if let Some(band) = args.get("--clink-band") {
        config.band = match band {
            "0" => 0,
            "1" => 1,
            _ => return Err("--clink-band must be 0 (2.4 GHz) or 1 (5 GHz)".into()),
        };
    }
    let mut link = oxp_link_linux::LinuxClinkLink::new(config)
        .map_err(|_| "Invalid BLE adapter, interface, or relay identity")?
        .with_listener(Arc::new(|state| event(link_event(state))));
    if let Some(address) = args.get("--clink-address") {
        link = link
            .with_address(address.into())
            .map_err(|_| "--clink-address must be a Bluetooth MAC address")?;
    }
    Ok(Some(Arc::new(link)))
}

fn pairing_from_args(args: &Args) -> Result<Pairing> {
    if let Some(token) = args.get("--token") {
        Ok(Pairing::Required(token.into()))
    } else if args.get("--pairing").is_some() {
        Ok(Pairing::Required(oxp_web::tls::random_token()?))
    } else {
        Ok(Pairing::Disabled)
    }
}

fn advertise_address(args: &Args) -> Result<Option<Ipv4Addr>> {
    args.get("--advertise-address")
        .map(|value| {
            let ip = value
                .parse::<Ipv4Addr>()
                .map_err(|_| "--advertise-address requires an IPv4 address")?;
            if ip.is_loopback() || ip.is_unspecified() || ip.is_multicast() || ip.is_broadcast() {
                return Err(
                    "--advertise-address requires a non-loopback unicast IPv4 address".into(),
                );
            }
            Ok(ip)
        })
        .transpose()
}

fn pairing_url(host: &str, tls: bool, pairing: &Pairing) -> String {
    let mut url = format!("{}://{host}/", if tls { "https" } else { "http" });
    if let Pairing::Required(token) = pairing {
        url.push_str("#k=");
        for b in token.bytes() {
            if b.is_ascii_alphanumeric() || b"-._~".contains(&b) {
                url.push(b as char);
            } else {
                url.push_str(&format!("%{b:02X}"));
            }
        }
    }
    url
}

type WireShutdown = Arc<dyn Fn() + Send + Sync>;

#[derive(Default)]
struct RelayShutdown {
    stopped: Arc<AtomicBool>,
    session_failed: AtomicBool,
    ended: AtomicBool,
    has_output: AtomicBool,
    link: Mutex<Option<Arc<dyn ClinkLink>>>,
    output: Mutex<Option<Arc<ClinkOutput>>>,
    hub: Mutex<Option<Arc<Hub>>>,
    wire: Mutex<Option<WireShutdown>>,
}
impl RelayShutdown {
    fn finish(&self) -> Result<()> {
        self.stopped.store(true, Ordering::Release);
        if let Some(hub) = self.hub.lock().unwrap().take() {
            hub.close();
        }
        if let Some(output) = self.output.lock().unwrap().take() {
            output.close();
            output.await_closed(Duration::from_millis(400));
        }
        let link = self.link.lock().unwrap().take();
        let cleanup = link.map(|link| link.disconnect());
        if let Some(shutdown) = self.wire.lock().unwrap().take() {
            shutdown();
        }
        self.emit_ended();
        cleanup.unwrap_or(Ok(()))
    }
    fn emit_ended(&self) {
        if self.has_output.load(Ordering::Acquire) && !self.ended.swap(true, Ordering::AcqRel) {
            Listener.state(ClinkState::Ended);
        }
    }
}
struct RelayListener(Arc<RelayShutdown>);
impl ClinkListener for RelayListener {
    fn state(&self, state: ClinkState) {
        if state == ClinkState::Ended {
            self.0.emit_ended();
            if !self.0.stopped.load(Ordering::Acquire) {
                self.0.session_failed.store(true, Ordering::Release);
            }
        } else {
            Listener.state(state);
        }
    }
    fn error(&self, message: &str) {
        Listener.error(message);
        self.0.session_failed.store(true, Ordering::Release);
    }
    fn diagnostic(&self, message: &str) {
        Listener.diagnostic(message);
    }
    fn statistics(&self, sent: u64, dropped: u64) {
        Listener.statistics(sent, dropped);
    }
    fn profile_mismatch(&self, input: &str, decoders: &[String]) {
        Listener.profile_mismatch(input, decoders);
    }
}

fn supervise(
    shutdown: Arc<RelayShutdown>,
    mut signal: impl FnMut() -> bool,
    work: impl FnOnce(Arc<RelayShutdown>) -> Result<()> + Send + 'static,
) -> Result<()> {
    let (tx, rx) = sync_channel(1);
    let state = shutdown.clone();
    std::thread::spawn(move || {
        let _ = tx.send(work(state));
    });
    let (result, interrupted) = loop {
        if signal() {
            break (Ok(()), true);
        }
        if shutdown.session_failed.load(Ordering::Acquire) {
            break (Err("Carlink session failed".into()), false);
        }
        match rx.recv_timeout(Duration::from_millis(20)) {
            Ok(result) => break (result, signal()),
            Err(RecvTimeoutError::Timeout) => {}
            Err(_) => break (Err("Relay worker stopped unexpectedly".into()), false),
        }
    };
    shutdown.stopped.store(true, Ordering::Release);
    let (tx, rx) = sync_channel(1);
    let cleanup_state = shutdown.clone();
    std::thread::spawn(move || {
        let _ = tx.send(cleanup_state.finish());
    });
    // Includes output stop, link leave and wire close, with margin for the shell's 3 s grace.
    let cleanup = rx
        .recv_timeout(Duration::from_secs(2))
        .unwrap_or_else(|_| Err("Relay cleanup timed out".into()));
    if let Some(close) = shutdown.wire.lock().unwrap().take() {
        close();
    }
    if interrupted {
        if cleanup.is_err() {
            event(json!({"type":"error","message":"Relay cleanup did not complete"}));
        }
        shutdown.emit_ended();
        Ok(())
    } else {
        result.and(cleanup)
    }
}

fn run() -> Result<()> {
    #[cfg(target_os = "linux")]
    let signals = oxp_link_linux::ShutdownSignals::install()?;
    supervise(
        Arc::new(RelayShutdown::default()),
        || {
            #[cfg(target_os = "linux")]
            {
                signals.requested()
            }
            #[cfg(not(target_os = "linux"))]
            {
                false
            }
        },
        run_relay,
    )
}

fn run_relay(shutdown: Arc<RelayShutdown>) -> Result<()> {
    let (args, sans) = arguments()?;
    if args.get("--help").is_some() {
        println!("{USAGE}");
        return Ok(());
    }
    if args.get("--unix").is_none() && args.get("--stdio").is_none() {
        return Err("Choose --unix PATH or --stdio".into());
    }
    let advertise = advertise_address(&args)?;
    let web = args.get("--web-port").is_some();
    event_stdout(args.get("--stdio").is_none());
    let link = clink_link(&args)?;
    *shutdown.link.lock().unwrap() = link.clone();
    let clink = link.is_some();
    if !web && !clink {
        return Err("At least one output is required".into());
    }
    let pin_stopped = shutdown.stopped.clone();
    if !web
        && (!sans.is_empty()
            || [
                "--web-root",
                "--web-bind",
                "--advertise-address",
                "--token",
                "--pairing",
                "--source-url",
                "--tls-cert",
                "--tls-key",
                "--tls-self-signed",
                "--no-tls",
            ]
            .iter()
            .any(|k| args.get(k).is_some()))
    {
        return Err("Web options require --web-port".into());
    }
    let mut outputs: Vec<Arc<dyn ProjectionOutput>> = Vec::new();
    let mut web_output = None;
    let mut pairing = None;
    if web {
        let cert = args.get("--tls-cert");
        let key = args.get("--tls-key");
        if cert.is_some() != key.is_some() {
            return Err("TLS requires --tls-cert and --tls-key".into());
        }
        if [
            cert.is_some(),
            args.get("--tls-self-signed").is_some(),
            args.get("--no-tls").is_some(),
        ]
        .into_iter()
        .filter(|v| *v)
        .count()
            != 1
        {
            return Err("Choose PEM TLS, self-signed TLS, or --no-tls".into());
        }
        if !sans.is_empty() && args.get("--tls-self-signed").is_none() {
            return Err("--tls-san requires --tls-self-signed".into());
        }
        let tls = if let (Some(cert), Some(key)) = (cert, key) {
            Tls::Pem {
                cert: cert.into(),
                key: key.into(),
            }
        } else if let Some(dir) = args.get("--tls-self-signed") {
            Tls::SelfSigned {
                state_dir: dir.into(),
                sans,
            }
        } else {
            Tls::Disabled
        };
        let web_pairing = pairing_from_args(&args)?;
        // A relay serves head units on the LAN; plain HTTP is only for local testing.
        let default_bind = if matches!(tls, Tls::Disabled) {
            "127.0.0.1"
        } else {
            "0.0.0.0"
        };
        let bind = std::net::SocketAddr::new(
            args.get("--web-bind").unwrap_or(default_bind).parse()?,
            args.need("--web-port")?.parse()?,
        );
        pairing = Some((web_pairing.clone(), !matches!(tls, Tls::Disabled)));
        let mut config =
            WebOutputConfig::new(bind, args.need("--web-root")?.into(), web_pairing, tls);
        if let Some(root) = args.get("--plugins-root") {
            config.plugins = Some(oxp_web::PluginsConfig {
                root: root.into(),
                enabled: args
                    .get("--plugins-enabled")
                    .unwrap_or("")
                    .split(',')
                    .filter(|s| !s.is_empty())
                    .map(str::to_owned)
                    .collect(),
                state_dir: args.get("--plugins-state-dir").map(Into::into),
                topics: args
                    .get("--plugins-topics")
                    .map(|s| {
                        s.split(',')
                            .filter(|t| !t.is_empty())
                            .map(str::to_owned)
                            .collect()
                    })
                    .unwrap_or_else(|| oxp_web::PluginsConfig::default().topics),
            });
        } else if args.get("--plugins-enabled").is_some()
            || args.get("--plugins-state-dir").is_some()
            || args.get("--plugins-topics").is_some()
        {
            return Err("Plugin options require --plugins-root".into());
        }
        if let Some(url) = args.get("--source-url") {
            config.source_url = url.into();
        }
        config.relay = args.get("--relay-name").map(|name| oxp_web::RelayInfo {
            name: name.into(),
            version: args.get("--relay-version").unwrap().into(),
        });
        config.diagnostics = Some(Arc::new(|id, value| {
            if value["type"] == "receiver-info" {
                let mut info = value.clone();
                info.as_object_mut().unwrap().remove("type");
                event(json!({"type":"receiver-info","client":id,"info":info}));
            } else {
                let mut stats = value.clone();
                stats["client"] = json!(id);
                event(stats);
            }
        }));
        let output = Arc::new(WebOutput::new(config)?);
        web_output = Some(output.clone());
        outputs.push(output);
    }
    if clink {
        let output = Arc::new(
            ClinkOutput::new(
                link.unwrap(),
                Arc::new(Pin {
                    digits: args.get("--pin").map(str::to_owned),
                    file: args.get("--pin-file").map(str::to_owned),
                    stopped: pin_stopped.clone(),
                }),
                Arc::new(FileTrust::new(Path::new(args.need("--state-dir")?))?),
            )?
            .with_options(oxp_clink::session::SessionOptions {
                ports: parse_ports(args.get("--port"))?,
                ..Default::default()
            })?
            .with_listener(Arc::new(RelayListener(shutdown.clone()))),
        );
        shutdown.has_output.store(true, Ordering::Release);
        *shutdown.output.lock().unwrap() = Some(output.clone());
        if transcode_enabled(args.get("--clink-transcode"))? {
            outputs.push(with_transcoding(
                output,
                oxp_transcode_linux::default_factory(),
                Arc::new(diagnostic),
            ));
        } else {
            outputs.push(output);
        }
    }
    let mut connection = connect(&args, true)?;
    *shutdown.wire.lock().unwrap() = Some(connection.shutdown.clone());
    if shutdown.stopped.load(Ordering::Acquire) {
        (connection.shutdown)();
        return Ok(());
    }
    handshake(&mut connection, "consumer", "relay")?;
    let (tx, rx) = sync_channel::<Message>(256);
    let mut writer = std::mem::replace(&mut connection.writer, Box::new(std::io::sink()));
    let stopped = shutdown.stopped.clone();
    let failed = Arc::new(AtomicBool::new(false));
    let worker_stopped = stopped.clone();
    let worker_failed = failed.clone();
    let wire_shutdown = connection.shutdown.clone();
    std::thread::spawn(move || loop {
        let m = match rx.recv_timeout(Duration::from_millis(100)) {
            Ok(m) => m,
            Err(RecvTimeoutError::Timeout) if !worker_stopped.load(Ordering::Acquire) => {
                continue;
            }
            Err(_) => break,
        };
        if let Err(e) = oxp_wire::write(&mut writer, &m) {
            event(json!({"type":"error","message":e.to_string()}));
            worker_failed.store(true, Ordering::Release);
            wire_shutdown();
            break;
        }
    });
    let log = EventLog::global().clone();
    let host = Arc::new(Sink {
        tx: tx.clone(),
        state: Mutex::new(("default".into(), [0; 256])),
        clock: Instant::now(),
        shutdown: connection.shutdown.clone(),
        failed: failed.clone(),
        log: log.clone(),
    });
    let hub = Hub::new(host);
    *shutdown.hub.lock().unwrap() = Some(hub.clone());
    let result = (|| -> Result<()> {
        for output in outputs {
            if shutdown.stopped.load(Ordering::Acquire) {
                return Ok(());
            }
            hub.add_output(output)?;
        }
        if let Some(web) = web_output {
            let addr = web.await_listening(Duration::from_secs(5))?;
            if let Some((web_pairing, tls)) = pairing {
                let host = if advertise.is_some() || addr.ip().is_unspecified() {
                    match lan_address(advertise) {
                        Some(ip) => std::net::SocketAddr::new(ip.into(), addr.port()).to_string(),
                        None => format!("localhost:{}", addr.port()),
                    }
                } else {
                    addr.to_string()
                };
                event(json!({"type":"pairing-url","url":pairing_url(&host, tls, &web_pairing)}));
            }
        }
        let mut consumer = Consumer::default();
        while let Some(message) = oxp_wire::read(&mut connection.reader)? {
            if let Message::Json(v) = &message {
                if v["type"] == "session" {
                    log.try_log(v.clone());
                }
            }
            consumer.receive(message, hub.as_ref())?;
        }
        Ok(())
    })();
    if let Err(e) = &result {
        let _ = tx.try_send(json_message(
            json!({"type":"error","code":"WIRE","message":e.to_string()}),
        ));
    }
    if failed.load(Ordering::Acquire) {
        return Err("Wire writer failed or callback queue overflowed".into());
    }
    result
}
fn main() {
    let result = run();
    // Events queued on the wire thread precede the final error, as when they were written directly.
    EventLog::flush_global(Duration::from_millis(500));
    if let Err(e) = result {
        event(json!({"type":"error","message":e.to_string()}));
        std::process::exit(1);
    }
}

/// The address a head unit most likely shares with the relay: prefer the default-route
/// interface, then Wi-Fi/Ethernet over USB gadgets and virtual interfaces, then private ranges
/// 192.168/16, 10/8 and 172.16/12. Loopback and non-private addresses are never auto-selected.
fn lan_address(advertise: Option<Ipv4Addr>) -> Option<Ipv4Addr> {
    let routes = std::fs::read_to_string("/proc/net/route").unwrap_or_default();
    lan_choice(&local_interfaces(), &default_routes(&routes), advertise)
}

fn local_interfaces() -> Vec<(String, Ipv4Addr)> {
    let commands: [(&str, &[&str]); 2] = [("ip", &["-o", "-4", "addr", "show"]), ("ifconfig", &[])];
    for (program, args) in commands {
        if let Ok(out) = std::process::Command::new(program).args(args).output() {
            if out.status.success() {
                let interfaces = parse_interfaces(&String::from_utf8_lossy(&out.stdout));
                if !interfaces.is_empty() {
                    return interfaces;
                }
            }
        }
    }
    Vec::new()
}

// Accept both ip -o output and Linux/BSD ifconfig output, including BusyBox's inet addr: form.
fn parse_interfaces(text: &str) -> Vec<(String, Ipv4Addr)> {
    let mut interfaces = Vec::new();
    let mut name = "";
    for line in text.lines() {
        let words: Vec<_> = line.split_whitespace().collect();
        if words.is_empty() {
            continue;
        }
        if words[0].trim_end_matches(':').parse::<u32>().is_ok() {
            name = words.get(1).copied().unwrap_or("");
        } else if !line.starts_with(char::is_whitespace) {
            name = words[0];
        }
        let name = name.trim_end_matches(':').split('@').next().unwrap_or("");
        for pair in words.windows(2) {
            if pair[0] == "inet" {
                if let Ok(ip) = pair[1]
                    .trim_start_matches("addr:")
                    .split('/')
                    .next()
                    .unwrap_or("")
                    .parse()
                {
                    interfaces.push((name.into(), ip));
                }
            }
        }
    }
    interfaces
}

fn default_routes(text: &str) -> Vec<(String, u32)> {
    text.lines()
        .filter_map(|line| {
            let fields: Vec<_> = line.split_whitespace().collect();
            if fields.get(1)? != &"00000000" || fields.get(7)? != &"00000000" {
                return None;
            }
            let flags = u32::from_str_radix(fields.get(3)?, 16).ok()?;
            if flags & 1 == 0 || flags & 0x200 != 0 {
                return None;
            }
            Some((fields[0].into(), fields.get(6)?.parse().ok()?))
        })
        .collect()
}

fn lan_choice(
    interfaces: &[(String, Ipv4Addr)],
    routes: &[(String, u32)],
    advertise: Option<Ipv4Addr>,
) -> Option<Ipv4Addr> {
    if advertise.is_some() {
        return advertise;
    }
    let default = routes.iter().min_by_key(|(name, metric)| (*metric, name));
    interfaces
        .iter()
        .filter(|(name, ip)| name != "lo" && name != "lo0" && !ip.is_loopback())
        .filter_map(|(name, ip)| {
            let range = match ip.octets() {
                [192, 168, ..] => 0,
                [10, ..] => 1,
                [172, b, ..] if (16..32).contains(&b) => 2,
                _ => return None,
            };
            let virtual_interface = [
                "usb", "rndis", "docker", "veth", "br-", "virbr", "tun", "tap", "wg",
            ]
            .iter()
            .any(|prefix| name.starts_with(prefix));
            let default_interface = default.is_some_and(|(interface, _)| interface == name);
            Some((!default_interface, virtual_interface, range, *ip))
        })
        .min()
        .map(|(_, _, _, ip)| ip)
}

#[cfg(test)]
mod tests {
    use super::*;

    struct FakeLink(Arc<std::sync::atomic::AtomicUsize>);
    impl ClinkLink for FakeLink {
        fn connect(
            &self,
            _: Arc<dyn Fn(Option<String>) + Send + Sync>,
        ) -> oxp_clink::Result<oxp_clink::ClinkConnection> {
            Err("fake auth failure".into())
        }
        fn disconnect(&self) -> oxp_clink::Result<()> {
            self.0.fetch_add(1, Ordering::AcqRel);
            Ok(())
        }
    }

    #[test]
    fn relay_identity_arguments_are_paired_and_bounded() {
        assert!(parse_arguments(Vec::<String>::new()).is_ok());
        assert!(parse_arguments(
            [
                "--relay-name",
                "OpenXPlay Linux",
                "--relay-version",
                "0.1.3"
            ]
            .map(String::from)
        )
        .is_ok());
        for values in [
            vec!["--relay-name", "Linux"],
            vec!["--relay-version", "0.1.3"],
            vec!["--relay-name", "", "--relay-version", "0.1.3"],
            vec!["--relay-name", "Linux", "--relay-version", "bad\nversion"],
        ] {
            assert!(parse_arguments(values.into_iter().map(String::from)).is_err());
        }
    }

    #[test]
    fn errors_disconnect_before_returning_to_main() {
        let calls = Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let count = calls.clone();
        let error = supervise(
            Arc::new(RelayShutdown::default()),
            || false,
            move |state| {
                *state.link.lock().unwrap() = Some(Arc::new(FakeLink(count)));
                Err("fake handshake failure".into())
            },
        )
        .unwrap_err();
        assert_eq!(error.to_string(), "fake handshake failure");
        assert_eq!(calls.load(Ordering::Acquire), 1);
    }

    #[test]
    fn signal_cleanup_is_bounded_even_when_disconnect_stalls() {
        struct SlowLink(Mutex<std::sync::mpsc::Receiver<()>>);
        impl ClinkLink for SlowLink {
            fn connect(
                &self,
                _: Arc<dyn Fn(Option<String>) + Send + Sync>,
            ) -> oxp_clink::Result<oxp_clink::ClinkConnection> {
                unreachable!()
            }
            fn disconnect(&self) -> oxp_clink::Result<()> {
                let _ = self.0.lock().unwrap().recv();
                Ok(())
            }
        }
        let state = Arc::new(RelayShutdown::default());
        let (release, wait) = sync_channel(1);
        *state.link.lock().unwrap() = Some(Arc::new(SlowLink(Mutex::new(wait))));
        let start = Instant::now();
        supervise(state, || true, |_| Ok(())).unwrap();
        assert!(start.elapsed() < Duration::from_millis(2500));
        release.send(()).unwrap();
    }

    #[test]
    fn signal_and_session_failure_interrupt_blocked_wire_and_disconnect() {
        for signal in [true, false] {
            let state = Arc::new(RelayShutdown::default());
            let calls = Arc::new(std::sync::atomic::AtomicUsize::new(0));
            *state.link.lock().unwrap() = Some(Arc::new(FakeLink(calls.clone())));
            let closed = Arc::new(AtomicBool::new(false));
            let wire_closed = closed.clone();
            *state.wire.lock().unwrap() = Some(Arc::new(move || {
                wire_closed.store(true, Ordering::Release);
            }));
            let (ready_tx, ready_rx) = sync_channel(1);
            let (release_tx, release_rx) = sync_channel(1);
            let work = move |state: Arc<RelayShutdown>| {
                if !signal {
                    RelayListener(state).error("fake auth failure");
                }
                ready_tx.send(()).unwrap();
                let _ = release_rx.recv();
                Ok(())
            };
            let mut ready = false;
            let start = Instant::now();
            let result = supervise(
                state.clone(),
                || {
                    if !ready {
                        ready = ready_rx.try_recv().is_ok();
                    }
                    ready && signal
                },
                work,
            );
            release_tx.send(()).unwrap();
            assert_eq!(result.is_ok(), signal);
            assert!(state.stopped.load(Ordering::Acquire));
            assert!(closed.load(Ordering::Acquire));
            assert_eq!(calls.load(Ordering::Acquire), 1);
            assert!(start.elapsed() < Duration::from_secs(1));
        }
    }

    #[test]
    fn plugins_topics_option_is_optional_and_preserves_csv() {
        assert!(args(&[]).get("--plugins-topics").is_none());
        assert_eq!(
            args(&["--plugins-topics", "session,activity,route,audioSource"])
                .get("--plugins-topics"),
            Some("session,activity,route,audioSource")
        );
    }

    fn args(values: &[&str]) -> Args {
        parse_arguments(values.iter().map(|v| (*v).to_owned()))
            .unwrap()
            .0
    }

    #[test]
    fn clink_modes_and_option_combinations() {
        assert!(clink_link(&args(&[])).unwrap().is_none());
        for values in [
            vec![
                "--clink-head-unit",
                "192.168.43.1",
                "--clink-local",
                "192.168.43.2",
                "--state-dir",
                "/tmp/state",
            ],
            vec![
                "--clink-link",
                "static",
                "--clink-head-unit",
                "192.168.43.1",
                "--clink-local",
                "192.168.43.2",
                "--state-dir",
                "/tmp/state",
                "--pin",
                "001234",
            ],
            vec![
                "--clink-link",
                "ble",
                "--state-dir",
                "/tmp/state",
                "--pin-file",
                "/tmp/pin",
            ],
            vec![
                "--clink-link",
                "ble",
                "--state-dir",
                "/tmp/state",
                "--clink-adapter",
                "hci1",
                "--clink-interface",
                "wlan1",
                "--clink-address",
                "AA:bb:CC:dd:EE:ff",
                "--clink-name",
                "OpenXPlay Linux",
                "--clink-model",
                "OpenXPlay Relay Linux",
                "--clink-id",
                "stable-id",
                "--clink-band",
                "0",
            ],
        ] {
            assert!(clink_link(&args(&values)).unwrap().is_some(), "{values:?}");
        }
        for values in [
            vec!["--clink-link", "unknown"],
            vec!["--clink-link", "static"],
            vec!["--clink-head-unit", "192.168.43.1"],
            vec!["--clink-local", "192.168.43.2"],
            vec!["--pin-file", "/tmp/pin"],
            vec!["--clink-adapter", "hci0"],
            vec!["--clink-band", "0"],
            vec!["--clink-transcode", "off"],
            vec![
                "--clink-head-unit",
                "192.168.43.1",
                "--clink-local",
                "192.168.43.2",
                "--state-dir",
                "/tmp/state",
                "--clink-transcode",
                "always",
            ],
        ] {
            assert!(clink_link(&args(&values)).is_err(), "{values:?}");
        }
        assert!(clink_link(&args(&[
            "--clink-link",
            "ble",
            "--state-dir",
            "/tmp/state",
            "--clink-transcode",
            "off",
        ]))
        .unwrap()
        .is_some());
        for extra in [
            vec!["--clink-head-unit", "192.168.43.1"],
            vec!["--clink-local", "192.168.43.2"],
            vec!["--pin", "12x"],
            vec!["--pin", "1234", "--pin-file", "/tmp/pin"],
            vec!["--pin-file", ""],
            vec!["--clink-address", "AA:BB:CC:DD:EE"],
            vec!["--clink-address", "AA:BB:CC:DD:EE:GG"],
            vec!["--clink-adapter", "bad"],
            vec!["--clink-interface", "bad/interface"],
            vec!["--clink-name", ""],
            vec!["--clink-model", ""],
            vec!["--clink-id", ""],
            vec!["--clink-band", "2"],
        ] {
            let mut values = vec!["--clink-link", "ble", "--state-dir", "/tmp/state"];
            values.extend(extra);
            assert!(clink_link(&args(&values)).is_err(), "{values:?}");
        }
        for values in [
            vec!["--clink-link"],
            vec!["--clink-link", "ble", "--clink-link", "ble"],
            vec!["--pin-file"],
        ] {
            assert!(parse_arguments(values.into_iter().map(str::to_owned)).is_err());
        }
    }

    #[test]
    fn pairing_arguments_default_random_explicit_and_precedence() {
        assert!(matches!(
            pairing_from_args(&args(&[])).unwrap(),
            Pairing::Disabled
        ));
        let Pairing::Required(code) = pairing_from_args(&args(&["--pairing", "--stdio"])).unwrap()
        else {
            panic!("Expected generated code")
        };
        assert_eq!(code.len(), 8);
        assert!(code.bytes().all(|b| b.is_ascii_digit()));
        for values in [
            vec!["--token", "custom"],
            vec!["--pairing", "--token", "custom"],
            vec!["--token", "custom", "--pairing"],
        ] {
            assert!(
                matches!(pairing_from_args(&args(&values)).unwrap(), Pairing::Required(code) if code == "custom")
            );
        }
        for values in [
            vec!["--pairing", "--pairing"],
            vec!["--token"],
            vec!["--pairing", "unexpected"],
        ] {
            assert!(parse_arguments(values.into_iter().map(str::to_owned)).is_err());
        }
    }

    #[test]
    fn pairing_urls_include_a_fragment_only_when_required() {
        assert_eq!(
            pairing_url("relay:8443", true, &Pairing::Disabled),
            "https://relay:8443/"
        );
        assert_eq!(
            pairing_url("127.0.0.1:8080", false, &Pairing::Disabled),
            "http://127.0.0.1:8080/"
        );
        assert_eq!(
            pairing_url("relay", true, &Pairing::Required("00123456".into())),
            "https://relay/#k=00123456"
        );
        assert_eq!(
            pairing_url("relay", false, &Pairing::Required("a /#é-._~".into())),
            "http://relay/#k=a%20%2F%23%C3%A9-._~"
        );
    }

    fn interfaces(list: &[(&str, &str)]) -> Vec<(String, Ipv4Addr)> {
        list.iter()
            .map(|(name, ip)| ((*name).into(), ip.parse().unwrap()))
            .collect()
    }

    #[test]
    fn pairing_host_prefers_the_lowest_metric_default_route() {
        let dongle = interfaces(&[("usb0", "192.168.68.1"), ("wlan0", "192.168.5.173")]);
        let routes = default_routes(
            "Iface Destination Gateway Flags RefCnt Use Metric Mask MTU Window IRTT
usb0 00000000 00000000 0001 0 0 600 00000000 0 0 0
wlan0 00000000 0105A8C0 0003 0 0 100 00000000 0 0 0
usb0 0044A8C0 00000000 0001 0 0 0 00FFFFFF 0 0 0
usb0 00000000 00000000 0000 0 0 0 00000000 0 0 0
malformed",
        );
        assert_eq!(routes.len(), 2);
        assert_eq!(
            lan_choice(&dongle, &routes, None),
            Some([192, 168, 5, 173].into())
        );
        assert_eq!(
            lan_choice(&dongle, &[("usb0".into(), 1), ("wlan0".into(), 2)], None),
            Some([192, 168, 68, 1].into())
        );
    }

    #[test]
    fn pairing_host_without_routes_prefers_physical_interfaces_then_private_ranges() {
        for name in [
            "usb0", "rndis0", "docker0", "veth0", "br-lan", "virbr0", "tun0", "tap0", "wg0",
        ] {
            let candidates = interfaces(&[(name, "192.168.68.1"), ("wlan0", "10.0.0.7")]);
            assert_eq!(
                lan_choice(&candidates, &[], None),
                Some([10, 0, 0, 7].into())
            );
        }
        for (list, expected) in [
            (
                vec![("eth0", "172.20.0.1"), ("wlan0", "10.0.0.7")],
                Some([10, 0, 0, 7].into()),
            ),
            (
                vec![("eth0", "10.0.0.7"), ("wlan0", "192.168.5.173")],
                Some([192, 168, 5, 173].into()),
            ),
            (
                vec![("eth0", "172.31.9.4"), ("wlan0", "172.32.0.1")],
                Some([172, 31, 9, 4].into()),
            ),
            (
                vec![
                    ("tun0", "198.18.0.1"),
                    ("wg0", "100.64.0.2"),
                    ("eth0", "169.254.3.3"),
                    ("lo", "127.0.0.1"),
                ],
                None,
            ),
        ] {
            assert_eq!(lan_choice(&interfaces(&list), &[], None), expected);
        }
        assert_eq!(lan_choice(&[], &[], None), None);
    }

    #[test]
    fn advertised_address_overrides_interfaces_and_routes_and_is_validated() {
        let override_ip =
            advertise_address(&args(&["--advertise-address", "192.168.9.8"])).unwrap();
        let dongle = interfaces(&[("usb0", "192.168.68.1"), ("wlan0", "192.168.5.173")]);
        assert_eq!(
            lan_choice(&dongle, &[("wlan0".into(), 100)], override_ip),
            override_ip
        );
        assert_eq!(lan_choice(&[], &[], override_ip), override_ip);
        assert_eq!(advertise_address(&args(&[])).unwrap(), None);
        for value in [
            "hostname",
            "::1",
            "192.168.1.999",
            "127.0.0.1",
            "0.0.0.0",
            "224.0.0.1",
            "255.255.255.255",
        ] {
            assert!(parse_arguments(["--advertise-address".into(), value.into()]).is_err());
        }
        assert!(parse_arguments(["--advertise-address".into()]).is_err());
    }

    #[test]
    fn interface_inventory_accepts_ip_and_ifconfig_formats() {
        let expected = interfaces(&[("usb0", "192.168.68.1"), ("wlan0", "192.168.5.173")]);
        assert_eq!(
            parse_interfaces(
                "2: usb0: <UP> inet 192.168.68.1/24 scope global usb0\n3: wlan0@if4: <UP> inet 192.168.5.173/24 scope global wlan0"
            ),
            expected
        );
        assert_eq!(
            parse_interfaces(
                "usb0 Link encap:Ethernet\n          inet addr:192.168.68.1 Bcast:192.168.68.255\nwlan0: flags=8863<UP>\n\tinet 192.168.5.173 netmask 0xffffff00"
            ),
            expected
        );
    }
}

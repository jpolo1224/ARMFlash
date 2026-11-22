//! Ruffle's network side, routed through ARMFlash's game server.
//!
//! Ruffle's own ExternalNavigatorBackend does the work (relative URLs, POST, cookies, loaders);
//! it is given the in-process game server as its HTTP proxy, so `http://host/path` resolves
//! from the mounted data packs exactly as it does for the WebView. The game server serves
//! https:// paths from the same place, so https requests are sent as plain http to it rather
//! than through a TLS tunnel.

use std::path::Path;
use std::time::Duration;

use indexmap::IndexMap;
use ruffle_core::backend::navigator::{
    ErrorResponse, NavigationMethod, NavigatorBackend, OwnedFuture, Request, SocketMode, SuccessResponse,
};
use ruffle_core::loader::Error;
use ruffle_core::socket::{SocketAction, SocketHandle};
use ruffle_frontend_utils::backends::navigator::{ExternalNavigatorBackend, FutureSpawner, NavigatorInterface};
use url::{ParseError, Url};

#[derive(Clone)]
pub struct GameNavigatorInterface;

impl NavigatorInterface for GameNavigatorInterface {
    fn navigate_to_website(&self, url: Url) {
        // Sponsor links and "more games" buttons: the game stays on screen.
        log::info!("ignored navigation to {url}");
    }

    async fn open_file(&self, path: &Path) -> std::io::Result<std::fs::File> {
        std::fs::File::open(path)
    }

    async fn confirm_socket(&self, _host: &str, _port: u16) -> bool {
        true
    }
}

pub struct GameNavigator<F: FutureSpawner<Error>> {
    inner: ExternalNavigatorBackend<F, GameNavigatorInterface>,
}

impl<F: FutureSpawner<Error> + 'static> GameNavigator<F> {
    pub fn new(movie_url: Url, proxy: Option<Url>, spawner: F, content: std::rc::Rc<ruffle_frontend_utils::content::PlayingContent>) -> Self {
        let inner = ExternalNavigatorBackend::new(
            movie_url,
            None,
            None,
            spawner,
            proxy,
            false, // never upgrade to https: the game server is plain http
            Default::default(),
            SocketMode::Allow,
            content,
            GameNavigatorInterface,
        );
        Self { inner }
    }
}

fn downgrade(url: &str) -> Option<String> {
    url.strip_prefix("https://").map(|rest| format!("http://{rest}"))
}

impl<F: FutureSpawner<Error> + 'static> NavigatorBackend for GameNavigator<F> {
    fn navigate_to_url(&self, url: &str, target: &str, vars_method: Option<(NavigationMethod, IndexMap<String, String>)>) {
        self.inner.navigate_to_url(url, target, vars_method)
    }

    fn fetch(&self, mut request: Request) -> OwnedFuture<Box<dyn SuccessResponse>, ErrorResponse> {
        if let Some(http) = downgrade(request.url()) {
            request.set_url(http);
        }
        self.inner.fetch(request)
    }

    fn resolve_url(&self, url: &str) -> Result<Url, ParseError> {
        self.inner.resolve_url(url)
    }

    fn spawn_future(&mut self, future: OwnedFuture<(), Error>) {
        self.inner.spawn_future(future)
    }

    fn pre_process_url(&self, url: Url) -> Url {
        self.inner.pre_process_url(url)
    }

    fn connect_socket(
        &mut self,
        host: String,
        port: u16,
        timeout: Duration,
        handle: SocketHandle,
        receiver: async_channel::Receiver<Vec<u8>>,
        sender: async_channel::Sender<SocketAction>,
    ) {
        self.inner.connect_socket(host, port, timeout, handle, receiver, sender)
    }
}

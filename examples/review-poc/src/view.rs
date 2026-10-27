//! What every page gets around its own data: who is signed in, where we are, the flash message.

use rocket::request::FlashMessage;
use rocket::serde::Serialize;
use rocket::time::OffsetDateTime;

#[derive(Serialize)]
#[serde(crate = "rocket::serde")]
pub struct User {
    pub name: &'static str,
    pub role: &'static str,
    pub initials: &'static str,
}

pub const REVIEWER: User = User { name: "Morgan Lee", role: "Reviewer", initials: "ML" };

#[derive(Serialize)]
#[serde(crate = "rocket::serde")]
pub(crate) struct Notice {
    kind: String,
    message: String,
}

/// The shell of every page (who is signed in, where we are, the flash message) around the data of the page itself.
#[derive(Serialize)]
#[serde(crate = "rocket::serde")]
pub struct Page<'a, T: Serialize> {
    title: &'a str,
    active: &'a str,
    user: &'a User,
    flash: Option<Notice>,
    #[serde(flatten)]
    data: T,
}

pub fn page<'a, T: Serialize>(title: &'a str, active: &'a str, flash: Option<FlashMessage<'_>>, data: T) -> Page<'a, T> {
    Page { title, active, user: &REVIEWER, flash: flash.map(|f| Notice { kind: f.kind().into(), message: f.message().into() }), data }
}

pub fn today() -> String {
    OffsetDateTime::now_utc().date().to_string()
}

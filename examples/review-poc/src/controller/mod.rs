//! One module per area of the app; each owns its routes, the data its templates receive and nothing that builds HTML.
//!
//! | module      | routes                                   | template               |
//! |-------------|------------------------------------------|------------------------|
//! | `dashboard` | `GET /`                                  | `pages/dashboard`      |
//! | `requests`  | `GET /requests`                          | `pages/requests`       |
//! | `submit`    | `GET /requests/new`, `POST /requests`    | `pages/new`            |
//! | `detail`    | `GET /requests/<id>`, `POST …/review`    | `pages/detail`         |
//! | `stats`     | `GET /stats`                             | `pages/stats`          |
//! | `errors`    | the 404 catcher                          | `pages/not_found`      |

pub mod dashboard;
pub mod detail;
pub mod errors;
pub mod requests;
pub mod stats;
pub mod submit;

pub fn routes() -> Vec<rocket::Route> {
    routes![
        dashboard::index,
        requests::index,
        submit::form, submit::create,
        detail::show, detail::review,
        stats::index,
    ]
}

pub fn catchers() -> Vec<rocket::Catcher> {
    catchers![errors::not_found]
}

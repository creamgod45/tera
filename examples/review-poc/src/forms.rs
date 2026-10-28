//! Input forms and their validation.

use rocket::form::{self, FromForm, FromFormField};
use rocket::time::Date;

#[derive(Debug, FromFormField)]
pub enum Department { Engineering, Design, Sales, Finance, Operations }

#[derive(Debug, FromFormField)]
pub enum Category { Travel, Equipment, Software, Training, Meals }

#[derive(Debug, FromFormField)]
pub enum Decision { Approve, Reject }

macro_rules! name_of {
    ($ty:ident { $($v:ident),* }) => {
        impl $ty { pub fn name(&self) -> &'static str { match self { $($ty::$v => stringify!($v)),* } } }
    };
}
name_of!(Department { Engineering, Design, Sales, Finance, Operations });
name_of!(Category { Travel, Equipment, Software, Training, Meals });

#[derive(Debug, FromForm)]
pub struct NewRequest<'v> {
    #[field(validate = len(2..=60))]
    pub applicant: &'v str,
    #[field(validate = contains('@').or_else(msg!("invalid email address")))]
    pub email: &'v str,
    pub department: Department,
    pub category: Category,
    #[field(validate = range(1..=50000))]
    pub amount: u32,
    pub date: Date,
    #[field(validate = len(10..=500))]
    pub reason: &'v str,
    pub urgent: bool,
}

fn needs_comment<'v>(comment: &str, decision: &Decision) -> form::Result<'v, ()> {
    if matches!(decision, Decision::Reject) && comment.trim().len() < 5 {
        Err(form::Error::validation("a rejection needs a comment of at least 5 characters"))?;
    }
    Ok(())
}

#[derive(Debug, FromForm)]
pub struct ReviewForm<'v> {
    pub decision: Decision,
    #[field(validate = needs_comment(&self.decision))]
    pub comment: &'v str,
}

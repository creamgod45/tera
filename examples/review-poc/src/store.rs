//! The domain: expense requests, their review, and the statistics over them. In memory, to keep the PoC about the templates.

use std::sync::Mutex;

use rocket::serde::Serialize;

pub const DEPARTMENTS: [&str; 5] = ["Engineering", "Design", "Sales", "Finance", "Operations"];
pub const CATEGORIES: [&str; 5] = ["Travel", "Equipment", "Software", "Training", "Meals"];

#[derive(Debug, Clone, Serialize)]
#[serde(crate = "rocket::serde")]
pub struct Review {
    pub reviewer: String,
    pub decision: &'static str,
    pub comment: String,
    pub at: String,
}

#[derive(Debug, Clone, Serialize)]
#[serde(crate = "rocket::serde")]
pub struct Request {
    pub id: u32,
    pub applicant: String,
    pub email: String,
    pub department: String,
    pub category: String,
    pub amount: u32,
    pub date: String,
    pub reason: String,
    pub urgent: bool,
    pub status: &'static str,
    pub submitted_at: String,
    pub review: Option<Review>,
}

#[derive(Debug, Default)]
pub struct Data {
    next_id: u32,
    pub items: Vec<Request>,
}

pub struct Store(pub Mutex<Data>);

impl Store {
    pub fn seeded() -> Self {
        // (applicant, department, category, amount, date, status, urgent)
        let seed: [(&str, &str, &str, u32, &str, &str, bool); 14] = [
            ("Ada Lovelace", "Engineering", "Software", 1200, "2026-05-12", "approved", false),
            ("Grace Hopper", "Engineering", "Equipment", 2400, "2026-06-03", "approved", false),
            ("Linus Park", "Engineering", "Travel", 860, "2026-07-21", "rejected", false),
            ("Mina Cho", "Design", "Software", 540, "2026-07-02", "approved", false),
            ("Noor Haddad", "Design", "Training", 1500, "2026-08-14", "pending", false),
            ("Omar Reyes", "Sales", "Travel", 3200, "2026-08-19", "approved", true),
            ("Priya Nair", "Sales", "Meals", 210, "2026-08-30", "approved", false),
            ("Quentin Ward", "Sales", "Travel", 4100, "2026-09-09", "pending", true),
            ("Rosa Klein", "Finance", "Training", 980, "2026-09-12", "rejected", false),
            ("Sam Ito", "Finance", "Software", 330, "2026-09-18", "approved", false),
            ("Tara Singh", "Operations", "Equipment", 5200, "2026-10-01", "pending", false),
            ("Umar Bello", "Operations", "Travel", 740, "2026-10-08", "approved", false),
            ("Vera Novak", "Engineering", "Training", 1900, "2026-10-15", "pending", false),
            ("Wen Zhao", "Design", "Meals", 95, "2026-10-20", "pending", false),
        ];
        let mut data = Data::default();
        for (applicant, department, category, amount, date, status, urgent) in seed {
            data.next_id += 1;
            let review = (status != "pending").then(|| Review {
                reviewer: "Morgan Lee".into(),
                decision: if status == "approved" { "approved" } else { "rejected" },
                comment: if status == "approved" { "Within policy.".into() } else { "Not covered by the budget.".into() },
                at: date.to_string(),
            });
            data.items.push(Request {
                id: data.next_id,
                applicant: applicant.into(),
                email: format!("{}@example.com", applicant.to_lowercase().replace(' ', ".")),
                department: department.into(),
                category: category.into(),
                amount,
                date: date.into(),
                reason: format!("{category} expense for the {department} team."),
                urgent,
                status: if status == "approved" { "approved" } else if status == "rejected" { "rejected" } else { "pending" },
                submitted_at: date.into(),
                review,
            });
        }
        Store(Mutex::new(data))
    }
}

impl Data {
    pub fn add(&mut self, mut request: Request) -> u32 {
        self.next_id += 1;
        request.id = self.next_id;
        self.items.push(request);
        self.next_id
    }

    pub fn get(&self, id: u32) -> Option<&Request> {
        self.items.iter().find(|r| r.id == id)
    }

    pub fn review(&mut self, id: u32, decision: &'static str, reviewer: &str, comment: &str, at: &str) -> bool {
        match self.items.iter_mut().find(|r| r.id == id && r.status == "pending") {
            Some(r) => {
                r.status = decision;
                r.review = Some(Review { reviewer: reviewer.into(), decision, comment: comment.into(), at: at.into() });
                true
            }
            None => false,
        }
    }
}

#[derive(Debug, Serialize)]
#[serde(crate = "rocket::serde")]
pub struct Group {
    pub name: String,
    pub count: u32,
    pub amount: u32,
    pub approved: u32,
}

#[derive(Debug, Serialize)]
#[serde(crate = "rocket::serde")]
pub struct Month {
    pub month: String,
    pub count: u32,
    pub amount: u32,
}

#[derive(Debug, Serialize)]
#[serde(crate = "rocket::serde")]
pub struct Stats {
    pub total: u32,
    pub pending: u32,
    pub approved: u32,
    pub rejected: u32,
    pub amount_total: u32,
    pub amount_approved: u32,
    pub amount_pending: u32,
    pub approval_rate: u32,
    pub by_department: Vec<Group>,
    pub by_category: Vec<Group>,
    pub monthly: Vec<Month>,
}

fn group(items: &[Request], names: &[&str], key: impl Fn(&Request) -> &str) -> Vec<Group> {
    names.iter().map(|name| {
        let mine: Vec<&Request> = items.iter().filter(|r| key(r) == *name).collect();
        Group {
            name: name.to_string(),
            count: mine.len() as u32,
            amount: mine.iter().map(|r| r.amount).sum(),
            approved: mine.iter().filter(|r| r.status == "approved").count() as u32,
        }
    }).collect()
}

pub fn stats(items: &[Request]) -> Stats {
    let count = |s: &str| items.iter().filter(|r| r.status == s).count() as u32;
    let sum = |s: &str| items.iter().filter(|r| r.status == s).map(|r| r.amount).sum::<u32>();
    let (approved, rejected) = (count("approved"), count("rejected"));
    let decided = approved + rejected;
    let mut months: Vec<Month> = vec![];
    for r in items {
        let month = r.date[..7].to_string();
        match months.iter_mut().find(|m| m.month == month) {
            Some(m) => { m.count += 1; m.amount += r.amount; }
            None => months.push(Month { month, count: 1, amount: r.amount }),
        }
    }
    months.sort_by(|a, b| a.month.cmp(&b.month));
    Stats {
        total: items.len() as u32,
        pending: count("pending"),
        approved,
        rejected,
        amount_total: items.iter().map(|r| r.amount).sum(),
        amount_approved: sum("approved"),
        amount_pending: sum("pending"),
        approval_rate: if decided == 0 { 0 } else { approved * 100 / decided },
        by_department: group(items, &DEPARTMENTS, |r| &r.department),
        by_category: group(items, &CATEGORIES, |r| &r.category),
        monthly: months,
    }
}

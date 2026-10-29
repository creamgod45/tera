// Toggle light / dark; "auto" follows the system until the visitor chooses.
(function () {
  const root = document.documentElement;
  const button = document.querySelector("[data-theme-toggle]");
  if (!button) return;
  button.addEventListener("click", function () {
    const dark = root.dataset.theme === "dark" ||
      (root.dataset.theme === "auto" && window.matchMedia("(prefers-color-scheme: dark)").matches);
    const next = dark ? "light" : "dark";
    root.dataset.theme = next;
    try { localStorage.setItem("theme", next); } catch (e) {}
  });
})();

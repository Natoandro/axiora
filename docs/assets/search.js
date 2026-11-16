/* Offline enhancement only: reading and navigation do not depend on JavaScript. */
(() => {
  "use strict";
  const form = document.getElementById("search-form");
  const input = document.getElementById("search-input");
  const list = document.getElementById("search-results");
  const status = document.getElementById("search-status");
  const index = window.AXIORA_SEARCH;
  if (form && Array.isArray(index)) {
    form.hidden = false;
    function search() {
      const query = input.value.trim().toLocaleLowerCase();
      list.replaceChildren();
      list.hidden = true;
      if (!query) { status.textContent = ""; return; }
      const terms = query.split(/\s+/).slice(0, 12);
      const matches = index.map(page => {
        const haystack = `${page.title} ${page.description} ${page.text}`.toLocaleLowerCase();
        const score = terms.every(term => haystack.includes(term))
          ? terms.reduce((sum, term) => sum + (page.title.toLocaleLowerCase().includes(term) ? 10 : 1), 0)
          : 0;
        return { page, score };
      }).filter(result => result.score > 0).sort((a, b) => b.score - a.score);
      status.textContent = matches.length ? `${matches.length} chapter${matches.length === 1 ? "" : "s"} found.` : "No chapters found. Try fewer words or another term.";
      for (const {page} of matches) {
        const item = document.createElement("li");
        const link = document.createElement("a");
        link.href = page.url;
        link.textContent = page.title;
        const excerpt = document.createElement("small");
        excerpt.textContent = page.description;
        item.append(link, excerpt);
        list.append(item);
      }
      list.hidden = !matches.length;
    }
    form.addEventListener("submit", event => { event.preventDefault(); search(); });
    input.addEventListener("input", search);
    input.addEventListener("keydown", event => {
      if (event.key === "Escape") { input.value = ""; search(); }
    });
  }
  const print = document.getElementById("print-button");
  if (print) { print.hidden = false; print.addEventListener("click", () => window.print()); }
  const chapters = document.querySelector(".chapter-nav");
  if (chapters && window.matchMedia("(max-width: 620px)").matches) chapters.open = false;
})();

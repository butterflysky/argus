document.addEventListener("DOMContentLoaded", () => {
  const root = document.querySelector("nav > ol.chapter");
  if (!root) return;

  // Hide numbering on the first entry (overview)
  const first = root.querySelector(":scope > li.chapter-item");
  if (first) {
    const s = first.querySelector("a > strong");
    if (s) s.remove();
  }

  const renumber = (ol, prefix = "") => {
    const items = Array.from(ol.children).filter((li) =>
      li.classList.contains("chapter-item")
    );
    items.forEach((li, idx) => {
      const anchor = li.querySelector(":scope > a");
      const strong = anchor ? anchor.querySelector("strong") : null;
      if (strong) {
        const num = prefix ? `${prefix}.${idx + 1}` : `${idx + 1}`;
        strong.textContent = `${num}.`;
      }
      const child = li.querySelector(":scope > ol.section");
      if (child) {
        const num = prefix ? `${prefix}.${idx + 1}` : `${idx + 1}`;
        renumber(child, num);
      }
    });
  };

  // Renumber starting from the second item as 1
  const rest = Array.from(root.children).filter((_, i) => i > 0);
  const tempOl = document.createElement("ol");
  tempOl.className = "chapter";
  rest.forEach((li) => tempOl.appendChild(li.cloneNode(true)));
  renumber(tempOl, "");

  // Apply numbers back to real nodes
  rest.forEach((li, idx) => {
    const targetStrong = li.querySelector(":scope > a > strong");
    const srcStrong = tempOl.children[idx].querySelector(":scope > a > strong");
    if (targetStrong && srcStrong) targetStrong.textContent = srcStrong.textContent;

    const targetSub = li.querySelector(":scope > ol.section");
    const srcSub = tempOl.children[idx].querySelector(":scope > ol.section");
    if (targetSub && srcSub) {
      const tStrong = targetSub.querySelectorAll("strong");
      const sStrong = srcSub.querySelectorAll("strong");
      tStrong.forEach((node, i) => {
        if (sStrong[i]) node.textContent = sStrong[i].textContent;
      });
    }
  });
});

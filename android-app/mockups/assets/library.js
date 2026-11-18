const courses = [
  {
    id: 'ideals', title: 'Prime and maximal ideals', subject: 'Abstract algebra',
    description: 'Commutative rings with identity. Assumes familiarity with ideals, quotient rings, integral domains, and fields.',
    current: 'Prime versus maximal ideals', start: 'question.html', version: '0.1.0',
    groups: {
      'Ideals and quotient rings': ['Recognize the ideal setting', 'Prime versus maximal ideals', 'Construct a proof, then check the quotient idea'],
    },
  },
  {
    id: 'linear', title: 'Linear maps', subject: 'Linear algebra', current: 'Kernel and image',
    groups: {
      'Vector spaces': ['Subspaces', 'Linear independence', 'Bases and dimension'],
      'Linear maps': ['Kernel and image', 'Rank–nullity', 'Composition', 'Isomorphisms'],
      'Matrix representations': ['Coordinates and bases', 'Matrix of a linear map', 'Change of basis', 'Similar matrices', 'Dual maps'],
    },
  },
  {
    id: 'sequences', title: 'Sequences and limits', subject: 'Real analysis', current: 'Subsequences',
    groups: {
      'Convergence': ['Definitions of convergence', 'Limit laws', 'Subsequences', 'Monotone sequences'],
      'Completeness': ['Cauchy sequences', 'Completeness of the reals', 'Limsup and liminf'],
    },
  },
  {
    id: 'groups', title: 'Groups and symmetry', subject: 'Abstract algebra', current: 'Cosets',
    groups: { 'Group structure': ['Groups and subgroups', 'Cyclic groups', 'Cosets', 'Lagrange’s theorem', 'Normal subgroups', 'Quotient groups'] },
  },
  {
    id: 'metric', title: 'Metric spaces', subject: 'Topology',
    groups: { 'Metric spaces': ['Metrics and examples', 'Open balls', 'Open and closed sets', 'Convergence', 'Compactness'] },
  },
  {
    id: 'proof', title: 'Methods of proof', subject: 'Foundations',
    groups: { 'Proof techniques': ['Direct proof', 'Contrapositive', 'Contradiction', 'Induction', 'Counterexamples'] },
  },
  {
    id: 'probability', title: 'Discrete probability', subject: 'Probability',
    groups: { 'Probability models': ['Sample spaces', 'Conditional probability', 'Independence', 'Discrete random variables'] },
  },
  {
    id: 'sets', title: 'Sets, functions, and relations', subject: 'Foundations',
    groups: { 'Sets and functions': ['Set operations', 'Images and preimages', 'Injections and surjections', 'Equivalence relations'] },
  },
];

const parameters = new URLSearchParams(location.search);
const byId = (id) => document.getElementById(id);
const matches = (text, term) => text.toLowerCase().includes(term);

function row(title, detail, href) {
  const item = document.createElement('li');
  item.className = 'catalog-row';
  const content = document.createElement(href ? 'a' : 'div');
  if (href) content.href = href;
  const heading = document.createElement('strong');
  heading.textContent = title;
  content.append(heading);
  if (detail) {
    const meta = document.createElement('small');
    meta.textContent = detail;
    content.append(meta);
  }
  item.append(content);
  return item;
}

const catalog = byId('catalog-list');

if (catalog) {
  const search = byId('catalog-search');
  const filter = byId('catalog-filter');
  const keepView = () => {
    const query = new URLSearchParams();
    if (search.value) query.set('q', search.value);
    if (filter.value !== 'all') query.set('show', filter.value);
    return query;
  };

  function renderCourses() {
    const term = search.value.trim().toLowerCase();
    const results = courses.filter((course) => {
      const state = course.current ? 'ongoing' : 'new';
      return (filter.value === 'all' || filter.value === state)
        && matches(course.title + ' ' + course.subject, term);
    });
    const view = keepView();
    byId('catalog-count').textContent = results.length
      ? `${results.length} of ${courses.length} courses`
      : 'No matching courses. Try another title or change the filter.';
    catalog.replaceChildren(...results.map((course) => {
      const route = new URLSearchParams(view);
      route.set('id', course.id);
      const units = Object.values(course.groups).flat().length;
      const state = course.current ? 'In progress' : 'Ready to start';
      return row(course.title, `${course.subject} · ${units} units · ${state}`, `course.html?${route}`);
    }));
  }

  search.value = parameters.get('q') ?? '';
  if (['ongoing', 'new'].includes(parameters.get('show'))) {
    filter.value = parameters.get('show');
  }
  search.addEventListener('input', renderCourses);
  filter.addEventListener('change', renderCourses);
  renderCourses();
}

if (byId('course-content')) {
  const course = courses.find((entry) => entry.id === parameters.get('id'));
  const title = byId('course-title');
  parameters.delete('id');
  byId('course-back').href = parameters.size ? `library.html?${parameters}` : 'library.html';

  if (!course) {
    title.textContent = 'Course unavailable';
    byId('course-description').textContent = 'Return to Library to choose a sample course.';
  } else {
    title.textContent = course.title;
    document.title = `${course.title} · Axiora`;
    byId('course-description').textContent = course.description ?? course.subject;
    const state = course.current ? `Next up · ${course.current}` : 'Ready to start';
    const source = course.version ? `Draft course · Version ${course.version}` : 'Sample outline';
    byId('course-status').textContent = `${source} · ${state}`;
    if (course.start) {
      byId('course-resume').href = course.start;
      byId('course-resume').hidden = false;
    }
    byId('course-outline').hidden = false;
    const search = byId('unit-search');
    const groups = byId('unit-groups');
    const total = Object.values(course.groups).flat().length;

    function renderUnits() {
      const term = search.value.trim().toLowerCase();
      let count = 0;
      const sections = [];
      for (const [name, titles] of Object.entries(course.groups)) {
        const results = titles.filter((unit) => matches(unit, term));
        if (!results.length) continue;

        count += results.length;
        const group = document.createElement('details');
        group.className = 'outline-group';
        group.open = Boolean(term) || titles.includes(course.current) || sections.length === 0;
        const summary = document.createElement('summary');
        summary.textContent = `${name} · ${results.length}`;
        const list = document.createElement('ul');
        list.className = 'catalog-list';
        for (const unit of results) {
          const active = unit === course.current;
          list.append(row(unit, active ? 'In progress' : '', active ? course.start : undefined));
        }
        group.append(summary, list);
        sections.push(group);
      }
      byId('unit-count').textContent = count
        ? `${count} of ${total} units`
        : 'No matching units. Try another title.';
      groups.replaceChildren(...sections);
    }

    search.addEventListener('input', renderUnits);
    renderUnits();
  }
}

const question = document.querySelector('#question');

if (question) {
  const choices = question.querySelector('fieldset');
  const submit = document.querySelector('#submit-answer');
  const feedback = document.querySelector('#feedback');
  const retry = document.querySelector('#retry');
  const showExample = document.querySelector('#show-example');
  const example = document.querySelector('#example');
  const exampleText = document.querySelector('#example-text');
  const nextProof = document.querySelector('#next-proof');
  let firstAnswer;

  question.addEventListener('change', () => {
    submit.disabled = false;
  });

  question.addEventListener('submit', (event) => {
    event.preventDefault();
    const answer = new FormData(question).get('answer');
    if (!answer) return;

    firstAnswer ??= answer;
    feedback.textContent = answer === 'no'
      ? 'Correct. A prime ideal can have a quotient that is an integral domain without being a field.'
      : 'The reverse implication is valid. Examine the zero ideal in the integers.';
    choices.disabled = true;
    retry.hidden = answer === 'no';
    showExample.hidden = false;
    submit.hidden = true;
    (retry.hidden ? showExample : retry).focus();
  });

  retry.addEventListener('click', () => {
    choices.disabled = false;
    question.reset();
    feedback.textContent = '';
    retry.hidden = true;
    showExample.hidden = true;
    submit.hidden = false;
    submit.disabled = true;
    choices.querySelector('input').focus();
  });

  showExample.addEventListener('click', () => {
    exampleText.textContent = firstAnswer === 'yes'
      ? 'The integers have no zero divisors, so (0) is prime. Yet (0) is strictly contained in (2), which is strictly contained in the integers. So (0) is not maximal.'
      : 'The quotient of the integers by (0) is an integral domain, so (0) is prime. The quotient is not a field, so (0) is not maximal.';
    example.hidden = false;
    retry.hidden = true;
    showExample.hidden = true;
    nextProof.hidden = false;
    example.tabIndex = -1;
    example.focus();
  });
}

const draft = document.querySelector('#draft');
const draftKey = 'axiora.mockup.prime-versus-maximal.proof';
const comparisonKey = draftKey + '.comparison';

if (draft) {
  const status = document.querySelector('#save-status');
  const save = document.querySelector('#save-draft');
  const compare = document.querySelector('#compare-proof');

  compare.addEventListener('click', (event) => {
    try {
      sessionStorage.setItem(comparisonKey, draft.value);
    } catch {
      event.preventDefault();
      status.textContent = 'Comparison is unavailable because this browser blocked session storage.';
    }
  });

  let comparisonDraft = null;
  try {
    comparisonDraft = sessionStorage.getItem(comparisonKey);
    if (comparisonDraft !== null) {
      draft.value = comparisonDraft;
      sessionStorage.removeItem(comparisonKey);
    }
  } catch {
    // A blocked comparison snapshot does not prevent editing the draft.
  }

  try {
    const savedDraft = localStorage.getItem(draftKey);
    if (comparisonDraft === null && savedDraft !== null) draft.value = savedDraft;
    if (draft.value !== (savedDraft ?? '')) {
      status.textContent = 'Unsaved changes';
      save.disabled = false;
    } else if (savedDraft !== null) {
      status.textContent = 'Saved in this browser';
    }
  } catch {
    status.textContent = 'Browser storage unavailable';
  }

  draft.addEventListener('input', () => {
    status.textContent = 'Unsaved changes';
    save.disabled = false;
  });

  save.addEventListener('click', () => {
    try {
      localStorage.setItem(draftKey, draft.value);
      status.textContent = 'Saved in this browser';
      save.disabled = true;
    } catch {
      status.textContent = 'Save failed. Copy your draft or try again.';
    }
  });
}

const simulateImport = document.querySelector('#simulate-import');

if (simulateImport) {
  simulateImport.disabled = false;
  simulateImport.addEventListener('click', () => {
    document.querySelector('#import-status').textContent = 'Import preview complete. You can now open the sample course.';
    simulateImport.hidden = true;
    document.querySelector('#cancel-import').hidden = true;
    const openSample = document.querySelector('#open-sample');
    openSample.hidden = false;
    openSample.focus();
  });
}

const rubric = document.querySelector('#proof-rubric');

if (rubric) {
  const draftLabel = document.querySelector('#comparison-draft-label');
  const draftText = document.querySelector('#comparison-draft-text');

  try {
    const snapshot = sessionStorage.getItem(comparisonKey);
    draftLabel.textContent = snapshot === null ? 'Sample draft' : 'Your draft';
    if (snapshot !== null) draftText.textContent = snapshot.trim() ? snapshot : 'Your draft is empty.';
  } catch {
    draftLabel.textContent = 'Sample draft';
  }

  const criteria = [
    'Use a proper maximal ideal in a commutative ring with identity.',
    'Justify why the quotient is a field, or give a direct argument.',
    'Explain why the quotient has no zero divisors, or show primality directly.',
    'Conclude that the ideal is prime.',
  ];

  criteria.forEach((criterion, index) => {
    const field = document.createElement('fieldset');
    field.className = 'rubric-item';
    const legend = document.createElement('legend');
    legend.textContent = criterion;
    field.append(legend);

    const choices = document.createElement('div');
    choices.className = 'rubric-options';

    for (const answer of ['Met', 'Not met', 'Unsure']) {
      const label = document.createElement('label');
      const radio = document.createElement('input');
      radio.type = 'radio';
      radio.name = 'criterion-' + index;
      radio.value = answer;
      label.append(radio, answer);
      choices.append(label);
    }
    field.append(choices);
    rubric.append(field);
  });
}

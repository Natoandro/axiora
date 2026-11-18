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

if (draft) {
  const status = document.querySelector('#save-status');
  const save = document.querySelector('#save-draft');
  const key = 'axiora.mockup.prime-versus-maximal.proof';

  try {
    const savedDraft = localStorage.getItem(key);
    if (savedDraft !== null) {
      draft.value = savedDraft;
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
      localStorage.setItem(key, draft.value);
      status.textContent = 'Saved in this browser';
      save.disabled = true;
    } catch {
      status.textContent = 'Save failed. Copy your draft or try again.';
    }
  });
}

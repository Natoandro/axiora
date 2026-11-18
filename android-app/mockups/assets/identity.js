const logoChoices = document.querySelector('#logo-choices');
const paletteLogos = document.querySelectorAll('.preview-mark img');

logoChoices.addEventListener('change', (event) => {
  if (event.target.name !== 'logo') return;

  for (const logo of paletteLogos) {
    logo.src = event.target.value;
  }
});

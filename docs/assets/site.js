const reveal = new IntersectionObserver((entries) => {
  entries.forEach((entry) => {
    if (entry.isIntersecting) {
      entry.target.classList.add('visible');
      reveal.unobserve(entry.target);
    }
  });
}, { threshold: 0.12 });

document.querySelectorAll('.reveal').forEach((element) => reveal.observe(element));

const downloadButton = document.querySelector('#download-button');
const releaseNote = document.querySelector('#release-note');

fetch('https://api.github.com/repos/jahruz67/CloudDrive/releases/latest')
  .then((response) => {
    if (!response.ok) throw new Error('No published release');
    return response.json();
  })
  .then((release) => {
    const apk = release.assets.find((asset) => asset.name.endsWith('.apk'));
    if (apk) downloadButton.href = apk.browser_download_url;
    downloadButton.firstChild.textContent = `Download ${release.tag_name} `;
    releaseNote.textContent = `${release.tag_name} · Android 8+ · APK from GitHub Releases`;
  })
  .catch(() => {
    // The link already falls back to the latest GitHub release page.
  });

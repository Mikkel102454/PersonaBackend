/** Reusable, non-blocking replacement for browser-native prompt and confirm UI. */
function ensureDialog() {
  let dialog = document.querySelector('#action-form-dialog');
  if (dialog) return dialog;
  dialog = document.createElement('dialog'); dialog.id = 'action-form-dialog';
  dialog.innerHTML = '<form method="dialog"><h2 id="action-form-title">Action</h2>'
    + '<label id="action-form-label"><span></span><input id="action-form-input" autocomplete="off"></label>'
    + '<p id="action-form-message"></p><menu><button value="cancel">Cancel</button>'
    + '<button id="action-form-submit" value="submit">Apply</button></menu></form>';
  document.body.append(dialog); return dialog;
}

function open({ message, value = '', input = true, confirmLabel = 'Apply' }) {
  const dialog = ensureDialog(), label = dialog.querySelector('#action-form-label');
  const field = dialog.querySelector('#action-form-input'), copy = dialog.querySelector('#action-form-message');
  const submit = dialog.querySelector('#action-form-submit');
  dialog.querySelector('#action-form-title').textContent = input ? 'Enter value' : 'Confirm action';
  label.hidden = !input; copy.hidden = input; label.querySelector('span').textContent = message;
  copy.textContent = message; field.value = value ?? ''; submit.textContent = confirmLabel;
  return new Promise(resolve => {
    const close = () => { dialog.removeEventListener('close', close); resolve(dialog.returnValue === 'submit'
      ? input ? field.value : true : input ? null : false); };
    dialog.addEventListener('close', close); dialog.showModal();
    queueMicrotask(() => (input ? field : submit).focus());
  });
}

export function requestText(message, value = '') { return open({ message, value, input: true }); }
export function requestConfirm(message, confirmLabel = 'Confirm') { return open({ message, input: false, confirmLabel }); }

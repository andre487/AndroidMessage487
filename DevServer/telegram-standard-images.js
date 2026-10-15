// Standard Telegram nodes send each image separately; no custom node is needed.
const result = $input.all();
const images = $('Webhook').first().json.body.images ?? [];
images.forEach((image, index) => {
  const kind = ['avatar', 'app_icon'].includes(image.kind) ? image.kind : 'attachment';
  result.push({ json: {
    telegram_operation: kind === 'attachment' && ['big_picture', 'gallery'].includes(image.presentation)
      ? 'sendPhoto' : 'sendDocument',
    caption: kind === 'avatar' ? 'Avatar' : kind === 'app_icon' ? 'Application icon' : '',
  }, binary: { data: { data: image.data_base64, mimeType: image.mime_type,
    fileName: `${kind}-${index + 1}.${image.mime_type === 'image/png' ? 'png' : 'jpg'}` } } });
});
return result;

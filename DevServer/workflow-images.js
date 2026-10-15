// The previous validation node checks image metadata and Base64 before this step.
const event = $('Webhook').first().json.body;
const binary = {};
const images = (event.images ?? []).map((image, index) => {
  const { data_base64, ...metadata } = image;
  binary[`image${index}`] = { data: data_base64, mimeType: image.mime_type,
    fileName: `${['avatar', 'app_icon'].includes(image.kind) ? image.kind : 'attachment'}-${index + 1}.${image.mime_type === 'image/png' ? 'png' : 'jpg'}` };
  return metadata;
});
return [{ json: { ...event, images, image_count: images.length }, binary }];

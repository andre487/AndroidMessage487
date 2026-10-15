const assert = require('node:assert/strict');
const { readFileSync, readdirSync } = require('node:fs');
const { resolve } = require('node:path');
const { test } = require('node:test');
const { runInNewContext } = require('node:vm');
const root = resolve(__dirname, '../../examples/n8n');
const files = readdirSync(`${root}/workflows`).filter(file => file.endsWith('.json'));
const load = file => JSON.parse(readFileSync(`${root}/workflows/${file}`, 'utf8'));
const text = JSON.parse(readFileSync(`${root}/payloads/text.json`, 'utf8'));
const images = JSON.parse(readFileSync(`${root}/payloads/images.json`, 'utf8'));
const execute = (workflow, name, event, input = []) => {
  const script = workflow.nodes.find(node => node.name === name).parameters.jsCode;
  return runInNewContext(`(function () { ${script}\n})()`, {
    $: node => { assert.equal(node, 'Webhook'); return { first: () => ({ json: { body: event } }) }; },
    $input: { all: () => input },
  });
};

test('portable imports have connected nodes, authentication, placeholders and no exported account data', () => {
  assert.equal(files.length, 6);
  for (const file of files) {
    const workflow = load(file);
    assert.equal(workflow.active, false);
    assert.deepEqual(Object.keys(workflow).sort(), ['active', 'connections', 'name', 'nodes', 'settings']);
    const names = new Set(workflow.nodes.map(node => node.name));
    assert.equal(names.size, workflow.nodes.length);
    for (const [name, outputs] of Object.entries(workflow.connections)) {
      assert.ok(names.has(name));
      for (const branch of outputs.main) for (const edge of branch) assert.ok(names.has(edge.node));
    }
    for (const node of workflow.nodes) {
      assert.equal(node.credentials, undefined);
      if (node.type.endsWith('.webhook')) assert.equal(node.parameters.authentication, 'headerAuth');
      if (node.parameters.chatId) assert.equal(node.parameters.chatId, "={{ $('Configure').first().json.chat_id }}");
      if (node.type.endsWith('.dataTable')) {
        assert.deepEqual(node.parameters.dataTableId, { __rl: true, mode: 'id',
          value: "={{ $('Configure').first().json.sent_parts_table_id }}" });
      }
      if (node.name === 'Configure') {
        for (const field of node.parameters.assignments.assignments) assert.match(field.value, /^REPLACE_WITH_/);
      }
    }
  }
});

test('all receivers validate events; text-only levels intentionally ignore image content', () => {
  for (const file of files.filter(file => !file.includes('cleanup'))) {
    const workflow = load(file);
    assert.equal(execute(workflow, 'Validate event', text)[0].json.valid, true);
    for (const bad of [null, { ...text, text: null }, { ...text, schema_version: 2 }, { ...text, images: {} }]) {
      assert.equal(execute(workflow, 'Validate event', bad)[0].json.valid, false);
    }
    assert.equal(execute(workflow, 'Validate event', images)[0].json.valid, true);
    const invalidImage = { ...images, images: [{ ...images.images[0], data_base64: 'bad!' }] };
    assert.equal(execute(workflow, 'Validate event', invalidImage)[0].json.valid, ['01', '03'].includes(file.slice(0, 2)));
    assert.equal(workflow.connections['Valid event'].main[1][0].node, 'Reject');
    assert.equal(workflow.nodes.find(node => node.name === 'Reject').parameters.options.responseCode, 400);
  }
});

test('webhook images become binary files without requiring Telegram or a custom node', () => {
  const workflow = load('02-webhook-images.json');
  const result = execute(workflow, 'Read images', images)[0];
  assert.equal(result.json.image_count, 5);
  assert.equal(result.json.images[0].data_base64, undefined);
  assert.ok(workflow.nodes.every(node => !/telegram/i.test(node.type)));
  for (let index = 0; index < 5; index++) {
    const file = result.binary[`image${index}`];
    const bytes = Buffer.from(file.data, 'base64');
    assert.equal(bytes.subarray(0, 8).toString('hex'), '89504e470d0a1a0a');
    assert.equal(bytes.readUInt32BE(16), images.images[index].width);
    assert.equal(bytes.readUInt32BE(20), images.images[index].height);
  }
  assert.equal(execute(workflow, 'Read images', text)[0].json.image_count, 0);
});

test('standard Telegram example preserves every image with only built-in nodes', () => {
  const workflow = load('04-telegram-images-standard.json');
  const formatted = execute(workflow, 'Prepare Telegram text', images);
  const parts = execute(workflow, 'Prepare image parts', images, formatted);
  assert.equal(parts.length, 6);
  assert.equal(parts.slice(1).map(part => part.json.telegram_operation).join(','),
    'sendPhoto,sendPhoto,sendPhoto,sendDocument,sendDocument');
  assert.ok(workflow.nodes.every(node => node.type.startsWith('n8n-nodes-base.')));
  parts.slice(1).forEach((part, index) => assert.equal(part.binary.data.data, images.images[index].data_base64));
  assert.equal(workflow.connections['Loop Over Parts'].main[0][0].node, 'Respond');
});

test('advanced Telegram example keeps all media in the text message and remembers only successful parts', () => {
  const workflow = load('05-telegram-images-advanced.json');
  const formatted = execute(workflow, 'Prepare Telegram text', images);
  const parts = execute(workflow, 'Prepare image parts', images, formatted);
  assert.equal(parts.length, 1);
  assert.equal(parts[0].json.telegram_operation, 'sendRichMessage');
  assert.equal(parts[0].json.rich_message.blocks.map(block => block.type).join(','), 'paragraph,photo,collage,collage');
  assert.equal(Object.keys(parts[0].binary).length, 5);
  const keys = execute(workflow, 'Create Part Keys', images, parts);
  assert.equal(keys[0].json.part_key, `${images.event_id}:1`);
  assert.equal(Object.keys(keys[0].binary).length, 5);
  for (const name of ['Send text', 'Send rich message']) {
    assert.equal(workflow.connections[name].main[0][0].node, 'Remember part key');
    assert.notEqual(workflow.nodes.find(node => node.name === name).onError, 'continueRegularOutput');
  }
  assert.equal(workflow.connections['Already sent'].main[0][0].node, 'Loop Over Parts');
  assert.equal(workflow.connections['Loop Over Parts'].main[0][0].node, 'Respond');
  assert.ok(!workflow.nodes.some(node => ['Send photo', 'Send file', 'Send album'].includes(node.name)));
});

test('optional cleanup starts as a dry run with an age filter', () => {
  const node = load('05-telegram-cleanup.json').nodes.find(node => node.name === 'Delete old keys');
  assert.equal(node.parameters.operation, 'deleteRows');
  assert.equal(node.parameters.options.dryRun, true);
  assert.equal(node.parameters.filters.conditions[0].keyName, 'createdAt');
});

#!/usr/bin/env ruby
require "json"
require "tmpdir"

# Exercise the real lane with a recording upload action; never contact Google.
module UI
  def self.user_error!(message) = raise(ArgumentError, message)
  def self.success(*) = nil
  def self.message(*) = nil
end

class LaneCheck
  attr_reader :uploads, :metadata
  def initialize(root = File.expand_path("..", __dir__))
    @lanes = {}
    @uploads = []
    path = File.expand_path("../fastlane/Fastfile", __dir__)
    instance_eval(File.read(path), File.join(root, "fastlane/Fastfile"))
  end
  def default_platform(*) = nil
  def opt_out_usage = nil
  def ensure_bundle_exec = nil
  def desc(*) = nil
  def platform(*) = yield
  def lane(name, &block) = @lanes[name] = block
  def upload_to_play_store(**options)
    @metadata = Dir.glob(File.join(options[:metadata_path], "**/*")).select { |path| File.file?(path) }
                   .to_h { |path| [path.delete_prefix("#{options[:metadata_path]}/"), File.read(path)] }
    @uploads << options
  end
  def run(**options) = @lanes.fetch(:play_release).call(options)
  def run_metadata(**options) = @lanes.fetch(:play_metadata).call(options)
end

def assert(value)
  raise "Assertion failed" unless value
end

Dir.mktmpdir("message487-play-test") do |dir|
  ENV["MESSAGE487_RELEASE_DIR"] = dir
  ENV.delete("SUPPLY_JSON_KEY_DATA")
  File.write(File.join(dir, "message487.aab"), "fixture")
  File.write(File.join(dir, "mapping.txt"), "fixture")
  check = LaneCheck.new
  check.run(dry_run: true)
  check.run(dry_run: true, validate_only: true)
  assert(check.uploads.empty?)
  [{dry_run: "yes"}, {validate_only: "yes"}, {track: " "},
   {release_status: "unknown"}, {validate_olny: true},
   {aab: File.join(dir, "missing.aab")}, {mapping: File.join(dir, "message487.aab")},
   {}].each do |options|
    begin
      check.run(**options)
      raise "Expected rejection: #{options}"
    rescue ArgumentError
      assert(check.uploads.empty?)
    end
  end
  ["invalid-json", "{}", '{"type":"authorized_user"}'].each do |key|
    ENV["SUPPLY_JSON_KEY_DATA"] = key
    begin
      check.run(validate_only: true)
      raise "Expected credentials rejection"
    rescue ArgumentError
      assert(check.uploads.empty?)
    end
  end
  ENV["SUPPLY_JSON_KEY_DATA"] = JSON.generate(type: "service_account", client_email: "test@example.invalid",
                                           private_key: "fixture", token_uri: "https://example.invalid/token")
  check.run(validate_only: true)
  upload = check.uploads.last
  assert(upload.values_at(:package_name, :track, :release_status, :validate_only) ==
         ["life.andre.message487", "internal", "draft", true])
  assert(upload[:mapping] == File.join(dir, "mapping.txt"))
  assert(upload[:skip_upload_metadata] && upload[:skip_upload_images] && upload[:skip_upload_screenshots])
  assert(!upload[:skip_upload_changelogs] && !upload[:skip_upload_aab] && upload[:skip_upload_apk])
  check.run(track: "production", release_status: "completed")
  assert(check.uploads.last.values_at(:track, :release_status, :validate_only) == ["production", "completed", false])
end
puts "play_release checks passed (no network calls)"

Dir.mktmpdir("message487-metadata-test") do |root|
  git = lambda do |*args|
    output, error, status = Open3.capture3("git", "-C", root, *args)
    raise error unless status.success?
    output.strip
  end
  git.call("init", "-q")
  git.call("config", "user.name", "Test")
  git.call("config", "user.email", "test@example.invalid")
  source = File.expand_path("../fastlane/metadata/android", __dir__)
  destination = File.join(root, "fastlane/metadata/android")
  FileUtils.mkdir_p(File.dirname(destination))
  FileUtils.cp_r(source, destination)
  git.call("add", ".")
  git.call("commit", "-qm", "Fixture")
  commit = git.call("rev-parse", "HEAD")
  check = LaneCheck.new(root)
  ENV.delete("PLAY_METADATA_COMMIT")
  check.run_metadata(metadata_commit: commit)
  original = check.metadata
  assert(original.size == 6)
  upload = check.uploads.last
  assert(upload[:skip_upload_aab] && upload[:skip_upload_apk] && upload[:skip_upload_changelogs])
  assert(upload[:skip_upload_images] && upload[:skip_upload_screenshots] && !upload[:skip_upload_metadata])
  assert(upload[:changes_not_sent_for_review] && !upload[:rescue_changes_not_sent_for_review])
  assert(!File.exist?(upload[:metadata_path]))
  title = File.join(destination, "en-US/title.txt")
  File.write(title, "Uncommitted text")
  check.run_metadata
  assert(check.metadata == original)
  [{metadata_commit: "--help"}, {metadata_commit: "0" * 40}, {typo: true}].each do |options|
    count = check.uploads.size
    begin
      check.run_metadata(**options)
      raise "Expected invalid revision/options rejection"
    rescue ArgumentError
      assert(check.uploads.size == count)
    end
  end
  ["", "x" * 31, "\xff".b, nil].each do |invalid|
    invalid ? File.binwrite(title, invalid) : File.delete(title)
    git.call("add", ".")
    git.call("commit", "-qm", "Invalid metadata")
    count = check.uploads.size
    begin
      check.run_metadata
      raise "Expected invalid/missing text rejection"
    rescue ArgumentError
      assert(check.uploads.size == count)
    end
  end
end
puts "play_metadata checks passed (no network calls)"

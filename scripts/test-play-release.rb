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
  attr_reader :uploads
  def initialize
    @lanes = {}
    @uploads = []
    path = File.expand_path("../fastlane/Fastfile", __dir__)
    instance_eval(File.read(path), path)
  end
  def default_platform(*) = nil
  def opt_out_usage = nil
  def ensure_bundle_exec = nil
  def desc(*) = nil
  def platform(*) = yield
  def lane(name, &block) = @lanes[name] = block
  def upload_to_play_store(**options) = @uploads << options
  def run(**options) = @lanes.fetch(:play_release).call(options)
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

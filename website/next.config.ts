import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Static HTML export (`out/`) — hosted on AWS Amplify, no Node server.
  output: "export",
  // Emit /about/index.html instead of /about.html so plain static hosting resolves clean URLs.
  trailingSlash: true,
  // The default image optimizer needs a server; static export serves images as-is.
  images: { unoptimized: true },
};

export default nextConfig;

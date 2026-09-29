import type { NextConfig } from "next";

const serverInternalUrl = (process.env.SERVER_INTERNAL_URL ?? "http://server:8080").replace(/\/$/, "");

const nextConfig: NextConfig = {
  async rewrites() {
    return [
      {
        source: "/api/:path*",
        destination: `${serverInternalUrl}/api/:path*`,
      },
    ];
  },
};

export default nextConfig;

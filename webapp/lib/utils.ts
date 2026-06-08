export const parseGithubUrl = (url: string) => {
  try {
    const trimmed = url.trim();
    const match = trimmed.match(/github\.com\/([^/]+)\/([^/]+)/);
    if (match && match[1] && match[2]) {
      return { owner: match[1], name: match[2].replace(/\.git$/, "") };
    }
  } catch {
    console.error("Invalid URL");
  }
  return null;
};

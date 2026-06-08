import { DOMAttributes, MouseEventHandler } from "react";
import styles from "./IconButton.module.scss";

export default function IconButton({
  icon,
  onClick,
  title,
}: {
  icon: React.ReactNode;
  onClick: MouseEventHandler<HTMLButtonElement>;
  title?: string;
}) {
  return (
    <button
      className={styles.iconButton}
      onClick={onClick}
      title={title}
      aria-label={title || "Icon Button"}
    >
      {icon}
    </button>
  );
}

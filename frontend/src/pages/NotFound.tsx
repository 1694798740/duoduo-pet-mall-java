import React from 'react';
import { Link } from 'react-router-dom';
import { Empty } from '../components/ui';

export const NotFoundPage: React.FC = () => (
  <Empty
    icon="🧭"
    title="页面不存在"
    desc="链接可能已失效，或者这个功能还没上线"
    action={
      <Link className="btn btn-primary" to="/">
        回到首页
      </Link>
    }
  />
);

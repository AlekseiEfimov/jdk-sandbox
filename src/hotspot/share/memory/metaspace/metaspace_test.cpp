/*
 * Copyright (c) 2018, 2019, SAP SE. All rights reserved.
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */


#include "precompiled.hpp"

#include "memory/virtualspace.hpp"
#include "memory/metaspace/virtualSpaceList.hpp"
#include "memory/metaspace/chunkManager.hpp"
#include "memory/metaspace/arenaGrowthPolicy.hpp"
#include "memory/metaspace/commitLimiter.hpp"
#include "memory/metaspace/metaspace_test.hpp"
#include "memory/metaspace/metaspaceArena.hpp"
#include "memory/metaspace/metaspaceContext.hpp"
#include "memory/metaspace/runningCounters.hpp"
#include "runtime/mutexLocker.hpp"
#include "utilities/debug.hpp"
#include "utilities/ostream.hpp"
#include "utilities/globalDefinitions.hpp"

namespace metaspace {

///// MetaspaceTestArena //////

MetaspaceTestArena::MetaspaceTestArena(Mutex* lock, MetaspaceArena* arena)
  : _lock(lock), _arena(arena) {}

MetaspaceTestArena::~MetaspaceTestArena() {
  delete _arena;
  delete _lock;
}

MetaWord* MetaspaceTestArena::allocate(size_t word_size) {
  return _arena->allocate(word_size);
}

void MetaspaceTestArena::deallocate(MetaWord* p, size_t word_size) {
  return _arena->deallocate(p, word_size);
}

///// MetaspaceTestArea //////

MetaspaceTestContext::MetaspaceTestContext(const char* name, size_t commit_limit, size_t reserve_limit)
  : _name(name), _reserve_limit(reserve_limit), _commit_limit(commit_limit),
    _context(NULL),
    _commit_limiter(commit_limit == 0 ? max_uintx : commit_limit), // commit_limit == 0 -> no limit
    _used_words_counter()
{

  if (reserve_limit > 0) {
    // have reserve limit -> non-expandable context
    ReservedSpace rs(reserve_limit * BytesPerWord, Metaspace::reserve_alignment(), false);
    _context = MetaspaceContext::create_nonexpandable_context(name, rs, &_commit_limiter);
  } else {
    // no reserve limit -> expandable vslist
    _context = MetaspaceContext::create_expandable_context(name, &_commit_limiter);
  }

}

MetaspaceTestContext::~MetaspaceTestContext() {
  DEBUG_ONLY(verify(true);)
  MutexLocker fcl(MetaspaceExpand_lock, Mutex::_no_safepoint_check_flag);
  delete _context;
}

// Create an arena, feeding off this area.
MetaspaceTestArena* MetaspaceTestContext::create_arena(MetaspaceType type) {
  const ArenaGrowthPolicy* growth_policy = ArenaGrowthPolicy::policy_for_space_type(type, false);
  Mutex* lock = new Mutex(Monitor::native, "MetaspaceTestArea-lock", false, Monitor::_safepoint_check_never);
  MetaspaceArena* arena = NULL;
  {
    MutexLocker ml(lock,  Mutex::_no_safepoint_check_flag);
    arena = new MetaspaceArena(_context->cm(), growth_policy, lock, &_used_words_counter, _name, false);
  }
  return new MetaspaceTestArena(lock, arena);
}

void MetaspaceTestContext::purge_area() {
  _context->cm()->purge();
}

#ifdef ASSERT
void MetaspaceTestContext::verify(bool slow) const {
  if (_context != NULL) {
    _context->verify(slow);
  }
}
#endif

void MetaspaceTestContext::print_on(outputStream* st) const {
  _context->print_on(st);
}

} // namespace metaspace


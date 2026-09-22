package com.travelmsg.domain

sealed trait Decision

// TODO(me): model Send and Suppress as case classes extending Decision.
// DecisionService and the controller are written against this exact shape:
//
  case class Send(travelerId: String, message: String) extends Decision
  case class Suppress(reason: String) extends Decision
//
// Every Suppress needs a human-readable `reason` - per CLAUDE.md, the audit
// trail of *why we didn't send* matters as much as the sends themselves.
// That's not just a Slice 1 nicety; later slices (capping, quiet hours,
// dedupe) all produce Suppress decisions and all need this field.
